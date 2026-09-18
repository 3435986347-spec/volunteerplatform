package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengde.activity.constant.ActivityStatus;
import com.hengde.activity.constant.EnrollmentStatus;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.dto.ProxyEnrollDTO;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityEnrollment;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.vo.MyEnrollmentVO;
import com.hengde.auth.service.SmsNotifyService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerProfileView;
import com.hengde.auth.constant.SanctionScope;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.sms.SmsNotifyTemplate;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.organization.biz.service.GroupQueryService;
import com.hengde.organization.exam.service.TempLeaderQueryService;
import com.hengde.organization.biz.service.SquadQueryService;
import org.redisson.api.RedissonClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Objects;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 志愿者端报名/取消/我的报名。
 *
 * <p>并发策略：以「志愿者维度」一把 Redisson 锁（{@code lock:enroll:volunteer:{id}}）串行化同一志愿者的
 * 报名/取消——防重复报名与「全平台同时间段冲突」都是志愿者维度的检查，一把锁即可覆盖。
 * slot 需求人数（need_count）V1 不做硬限（仅展示/目标值，超员由后台审核兜底），故无需 slot 维度锁。</p>
 *
 * <p>锁与事务的次序：锁在事务之外获取，临界区内用 {@link TransactionTemplate} 显式提交，
 * 提交完成后才在 finally 释放锁——避免「先放锁、后提交」窗口里另一请求读到未提交数据导致重复插入。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class EnrollmentService {

    /** 已发布 */
    private static final int STATUS_ACTIVITY_PUBLISHED = ActivityStatus.PUBLISHED;
    /** 报名：待审核 */
    private static final int ENROLL_PENDING = EnrollmentStatus.PENDING;
    /** 报名：已通过 */
    private static final int ENROLL_APPROVED = EnrollmentStatus.APPROVED;
    /** 报名：已取消 */
    private static final int ENROLL_CANCELLED = EnrollmentStatus.CANCELLED;

    /** 短信里的时间写法，与管理端报名审核那条保持一致 */
    private static final DateTimeFormatter ENROLL_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 账号正常状态（com.hengde.common.constant.UserStatus.NORMAL） */
    private static final int USER_STATUS_NORMAL = 0;

    /** 「志愿者维度」锁 key 前缀（与 organization 的 lock:group:volunteer: 隔离，互不抢占） */
    private static final String LOCK_KEY_PREFIX = "lock:enroll:volunteer:";

    /** 代报名一次最多 batch 上限（含自己）：避免恶意大批量占锁/打表 */
    private static final int PROXY_BATCH_MAX = 20;

    private ActivityMapper activityMapper;
    private ActivitySlotMapper activitySlotMapper;
    private ActivityEnrollmentMapper enrollmentMapper;
    private ActivityAttendanceMapper attendanceMapper;
    private VolunteerQueryService volunteerQueryService;
    private SmsNotifyService smsNotifyService;
    private GroupQueryService groupQueryService;
    private SquadQueryService squadQueryService;
    private TempLeaderQueryService tempLeaderQueryService;

    @Autowired
    public void setTempLeaderQueryService(TempLeaderQueryService tempLeaderQueryService) {
        this.tempLeaderQueryService = tempLeaderQueryService;
    }

    @Autowired
    public void setSquadQueryService(SquadQueryService squadQueryService) {
        this.squadQueryService = squadQueryService;
    }
    private RedissonClient redissonClient;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setActivityMapper(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    @Autowired
    public void setActivitySlotMapper(ActivitySlotMapper activitySlotMapper) {
        this.activitySlotMapper = activitySlotMapper;
    }

    @Autowired
    public void setEnrollmentMapper(ActivityEnrollmentMapper enrollmentMapper) {
        this.enrollmentMapper = enrollmentMapper;
    }

    @Autowired
    public void setAttendanceMapper(ActivityAttendanceMapper attendanceMapper) {
        this.attendanceMapper = attendanceMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setSmsNotifyService(SmsNotifyService smsNotifyService) {
        this.smsNotifyService = smsNotifyService;
    }

    @Autowired
    public void setGroupQueryService(GroupQueryService groupQueryService) {
        this.groupQueryService = groupQueryService;
    }

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    private com.hengde.auth.service.SanctionQueryService sanctionQueryService;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSanctionQueryService(com.hengde.auth.service.SanctionQueryService sanctionQueryService) {
        this.sanctionQueryService = sanctionQueryService;
    }

    /**
     * 报名：选定活动下若干时间段。校验链见方法体；need_audit 决定落库初值（0待审核/1已通过）。
     *
     * @return 实际新增的报名记录条数（= 报名的时间段数）
     */
    public int enroll(Long activityId, List<Long> slotIds, Long volunteerId) {
        // 去重，保留选择顺序。（处置闸门在 doEnroll 里、锁与事务之内——理由见那里）
        List<Long> distinctSlotIds = new ArrayList<>(new LinkedHashSet<>(slotIds));
        return DistributedLockSupport.runLocked(redissonClient, LOCK_KEY_PREFIX + volunteerId,
                () -> transactionTemplate.execute(s -> doEnroll(activityId, distinctSlotIds, volunteerId)));
    }

    /**
     * 这批人里有没有人还没到报名开放时间（V8 三个开放时间，V4 临时负责人考试批起全部生效）。
     *
     * <p><b>志愿者开放时间是底线</b>：留空＝即刻开放，这时谁都不用再查。管理团队（{@code manager_flag=1}）与活动临时负责人
     * （现在有效的资格，{@link TempLeaderQueryService}）<b>若活动另设了自己那一档的时间，按更早的那个算</b>。
     * 角色那一档<b>留空＝没有提前</b>，不是「即时可报」——此前这两列从没生效过，只填了志愿者时间的存量活动若按「留空即时」解读，
     * 管理团队与临时负责人会在上线那一刻突然能报所有还没开放的活动。</p>
     */
    private boolean notOpenYet(Activity activity, List<Long> volunteerIds, LocalDateTime now) {
        LocalDateTime base = activity.getEnrollOpenVolunteer();
        if (base == null || !now.isBefore(base)) {
            return false;
        }
        boolean managerOpen = activity.getEnrollOpenManager() != null && !now.isBefore(activity.getEnrollOpenManager());
        boolean leaderOpen = activity.getEnrollOpenLeader() != null && !now.isBefore(activity.getEnrollOpenLeader());
        if (!managerOpen && !leaderOpen) {
            return true;
        }
        Set<Long> early = new HashSet<>();
        if (managerOpen) {
            early.addAll(volunteerQueryService.filterManagers(volunteerIds));
        }
        if (leaderOpen) {
            early.addAll(tempLeaderQueryService.filterTempLeaders(volunteerIds));
        }
        return !early.containsAll(volunteerIds);
    }

    private int doEnroll(Long activityId, List<Long> slotIds, Long volunteerId) {
        // 处置闸门（V32）：被「限制参加活动」或「拒绝使用本程序」的人不得报名。
        // 【在锁与事务之内】口径是「审核通过即刻生效」，若放在锁外，
        // 「查过没被限制 → 处罚在这一刻生效 → 提交报名」这个窗口会漏掉一次刚生效的处罚。
        sanctionQueryService.assertNotRestricted(volunteerId, SanctionScope.ACTIVITY, "报名");
        LocalDateTime now = LocalDateTime.now();

        Activity activity = activityMapper.selectById(activityId);
        if (activity == null || !Integer.valueOf(STATUS_ACTIVITY_PUBLISHED).equals(activity.getStatus())) {
            throw new BusinessException("活动不存在");
        }
        if (notOpenYet(activity, List.of(volunteerId), now)) {
            throw new BusinessException("尚未开放报名");
        }
        // 报名截止：留空/脏数据按活动结束时间兜底（活动结束只改 run_status、status 仍=已发布，否则会允许活动后继续报名）
        LocalDateTime enrollDl = activity.getEnrollDeadline() != null ? activity.getEnrollDeadline() : activity.getEndTime();
        if (enrollDl != null && now.isAfter(enrollDl)) {
            throw new BusinessException("报名已截止");
        }

        // 选定的时间段必须都属于该活动
        List<ActivitySlot> slots = activitySlotMapper.selectList(Wrappers.<ActivitySlot>lambdaQuery()
                .eq(ActivitySlot::getActivityId, activityId)
                .in(ActivitySlot::getId, slotIds));
        if (slots.size() != slotIds.size()) {
            throw new BusinessException("存在无效的时间段");
        }

        // 项目数量上下限
        int n = slotIds.size();
        Integer min = activity.getMinProjects();
        Integer max = activity.getMaxProjects();
        if (min != null && min > 0 && n < min) {
            throw new BusinessException("至少需报名 " + min + " 个项目");
        }
        if (max != null && n > max) {
            throw new BusinessException("最多可报名 " + max + " 个项目");
        }

        // 资格校验
        VolunteerProfileView profile = volunteerQueryService.getProfileForEligibilityForShare(volunteerId);
        if (profile == null) {
            throw new BusinessException("志愿者信息不存在");
        }
        if (profile.status() == null || profile.status() != USER_STATUS_NORMAL) {
            throw new BusinessException("账号状态异常，无法报名");
        }
        checkEligibility(activity, profile, volunteerId);

        // 防重：该活动已有活跃报名（待审核/已通过）即拒绝；取消(3)/拒绝(2)后可再报
        Long active = enrollmentMapper.selectCount(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, activityId)
                .eq(ActivityEnrollment::getVolunteerId, volunteerId)
                .in(ActivityEnrollment::getStatus, ENROLL_PENDING, ENROLL_APPROVED));
        if (active != null && active > 0) {
            throw new BusinessException("您已报名该活动");
        }

        // 全平台同时间段防重：选定 slot 不得与「我在任何活动的活跃报名」slot 时间重叠，且选定 slot 彼此不重叠
        checkTimeConflicts(volunteerId, slots);

        int initStatus = Integer.valueOf(1).equals(activity.getNeedAudit()) ? ENROLL_PENDING : ENROLL_APPROVED;
        for (ActivitySlot slot : slots) {
            ActivityEnrollment e = new ActivityEnrollment();
            e.setActivityId(activityId);
            e.setSlotId(slot.getId());
            e.setVolunteerId(volunteerId);
            e.setStatus(initStatus);
            e.setEnrollTime(now);
            enrollmentMapper.insert(e);
        }
        return slots.size();
    }

    /**
     * 后台补录报名后通知本人（{@code enrollment-approved}）。
     *
     * <p><b>与代报名同一个理由</b>：这一笔报名不是他自己点的，管理员在后台加的，他事先并不知情——
     * 不通知就可能到了活动当天才发现自己在名单上。补录直接落「已通过」，
     * 故用「报名审核通过」那条模板（正文「您已成功报名…请准时参加」）而不是代报名那条。</p>
     *
     * <p>时间取<b>最早的那个场次</b>：补录可以一次录多场，短信只放得下一个时间，
     * 写最早的那场才是他需要最先到场的时刻。</p>
     */
    private void notifyManualEnrolled(Activity activity, List<ActivitySlot> slots, Long volunteerId) {
        try {
            LocalDateTime start = slots.stream()
                    .map(ActivitySlot::getStartTime)
                    .filter(Objects::nonNull)
                    .min(LocalDateTime::compareTo)
                    .orElse(activity.getStartTime());
            smsNotifyService.notifyVolunteer(volunteerId, SmsNotifyTemplate.ENROLLMENT_APPROVED,
                    SmsNotifyTemplate.ENROLLMENT_APPROVED.params(
                            activity.getTitle(),
                            start == null ? "" : ENROLL_TIME_FMT.format(start),
                            activity.getLocation()));
        } catch (Exception ex) {
            log.error("[SMS-NOTIFY] 后台补录报名通知失败 activityId={} volunteerId={}",
                    activity.getId(), volunteerId, ex);
        }
    }

    /**
     * 管理端手动新增报名（管理员代加志愿者，越权补录）。
     *
     * <p>与志愿者自助报名的差异：跳过资格校验（年龄/年级/性别/次数）与报名截止——补录场景常发生在截止后或
     * 不满足资格时；状态直接置为「已通过」并记审核人。但仍保留两条不可越的红线：
     * 同活动防重 + 全平台同时间段冲突（双重占用无意义）。复用同一把「志愿者维度」锁，与该志愿者的自助报名互斥。</p>
     *
     * @return 新增的报名记录条数
     */
    public int manualEnroll(Long activityId, Long volunteerId, List<Long> slotIds, Long adminId) {
        // 处置闸门（V32）：后台补录同样不得绕过。它确实是管理员的越权动作，
        // 但「越过报名条件」与「越过一条正在执行的处罚」是两回事——
        // 后者若能被一次补录悄悄抵消，处罚就不再是处罚。要放行请先显式解除处置（/lift-sanction）。
        List<Long> distinctSlotIds = new ArrayList<>(new LinkedHashSet<>(slotIds));
        return DistributedLockSupport.runLocked(redissonClient, LOCK_KEY_PREFIX + volunteerId,
                () -> transactionTemplate.execute(s -> doManualEnroll(activityId, distinctSlotIds, volunteerId, adminId)));
    }

    private int doManualEnroll(Long activityId, List<Long> slotIds, Long volunteerId, Long adminId) {
        // 处置闸门（V32）：后台补录同样不得绕过（在锁与事务之内，理由同 doEnroll）。
        // 它确实是管理员的越权动作，但「越过报名条件」与「越过一条正在执行的处罚」是两回事——
        // 后者若能被一次补录悄悄抵消，处罚就不再是处罚。要放行请先显式解除处置（/lift-sanction）。
        sanctionQueryService.assertNotRestricted(volunteerId, SanctionScope.ACTIVITY, "被补录报名");
        LocalDateTime now = LocalDateTime.now();

        Activity activity = activityMapper.selectById(activityId);
        if (activity == null || !Integer.valueOf(STATUS_ACTIVITY_PUBLISHED).equals(activity.getStatus())) {
            throw new BusinessException("活动不存在");
        }
        // 越权仅跳过「资格条件」（年龄/年级/性别/次数/截止）；账号存在与「未被禁用」不是资格条件，仍须拦截。
        VolunteerProfileView profile = volunteerQueryService.getProfileForEligibilityForShare(volunteerId);
        if (profile == null) {
            throw new BusinessException("志愿者不存在");
        }
        if (profile.status() == null || profile.status() != USER_STATUS_NORMAL) {
            throw new BusinessException("该志愿者账号状态异常，无法报名");
        }

        List<ActivitySlot> slots = activitySlotMapper.selectList(Wrappers.<ActivitySlot>lambdaQuery()
                .eq(ActivitySlot::getActivityId, activityId)
                .in(ActivitySlot::getId, slotIds));
        if (slots.size() != slotIds.size()) {
            throw new BusinessException("存在无效的时间段");
        }

        Long active = enrollmentMapper.selectCount(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, activityId)
                .eq(ActivityEnrollment::getVolunteerId, volunteerId)
                .in(ActivityEnrollment::getStatus, ENROLL_PENDING, ENROLL_APPROVED));
        if (active != null && active > 0) {
            throw new BusinessException("该志愿者已报名该活动");
        }

        checkTimeConflicts(volunteerId, slots);

        for (ActivitySlot slot : slots) {
            ActivityEnrollment e = new ActivityEnrollment();
            e.setActivityId(activityId);
            e.setSlotId(slot.getId());
            e.setVolunteerId(volunteerId);
            e.setStatus(ENROLL_APPROVED);
            e.setEnrollTime(now);
            e.setAuditBy(adminId);
            e.setAuditTime(now);
            enrollmentMapper.insert(e);
        }
        notifyManualEnrolled(activity, slots, volunteerId);
        return slots.size();
    }

    /**
     * 代报名（同小组成员之间）：actor 替 targets 一次性给某活动报若干时间段。
     *
     * <p>语义：
     * <ul>
     *   <li>actor 与所有 target 必须在同一 ACTIVE 小组（由 {@link GroupQueryService#requireSameActiveGroup} 兜底）。</li>
     *   <li>每个 target 仍走完整资格校验（年龄/年级/性别/已参加次数/账号状态）——代报名不越权。</li>
     *   <li>任一 target 校验失败 → 单事务整批回滚，避免「张三成了、李四没成」的部分成功状态。</li>
     *   <li>{@code proxy_by_volunteer_id} 落库为 actor，后台报名列表可以追溯代报名来源。</li>
     *   <li>报名是否要审核仍尊重 {@code activity.needAudit}——target 的入库初值与自助报名一致。</li>
     * </ul></p>
     *
     * <p>并发：按 target id 升序逐个 {@code tryLock}，避免和并发自助报名 (单锁) 死锁；
     * 单事务保证整批原子；释放顺序与获取相反。批量上限 {@link #PROXY_BATCH_MAX}。</p>
     *
     * @return 实际新增的报名记录条数（= target 数 × slot 数）
     */
    public int proxyEnroll(Long activityId, ProxyEnrollDTO dto, Long actorId) {
        // 1. 先做廉价的本地校验：去重 + 上限 + 排序锁顺序——任何远程查询前先把异常 payload 挡在门外
        List<Long> targets = new ArrayList<>(new LinkedHashSet<>(dto.getVolunteerIds()));
        if (targets.size() > PROXY_BATCH_MAX) {
            throw new BusinessException("一次最多代报名 " + PROXY_BATCH_MAX + " 名同组成员");
        }
        Collections.sort(targets);
        List<Long> distinctSlotIds = new ArrayList<>(new LinkedHashSet<>(dto.getSlotIds()));

        // 2. 同小组校验由 doProxyEnroll 在事务内再做一次：避免「校验通过 → 加锁 → 被移出组」TOCTOU 窗口
        //    DistributedLockSupport.runLockedMany 内部对 targets 升序去重后按序加锁（死锁安全）
        return DistributedLockSupport.runLockedMany(redissonClient, LOCK_KEY_PREFIX, targets,
                () -> transactionTemplate.execute(s -> doProxyEnroll(activityId, distinctSlotIds, targets, actorId)));
    }

    private int doProxyEnroll(Long activityId, List<Long> slotIds, List<Long> targets, Long actorId) {
        // 事务+锁内再校验同组：把 TOCTOU 窗口缩到「再校验 → 提交」的几毫秒内
        groupQueryService.requireSameActiveGroup(actorId, targets);

        // 处置闸门（V32）：【报名不止 enroll 一个入口】，代报名同样能把人放进活动，
        // 只挡自助报名等于留了一扇后门——同组的人替他报一次，处罚就绕过去了。
        //
        // 【必须放在同组校验之后】处置报错里带着「限制到什么时候」，
        // 放在前面等于任何人传一串 id 就能探出这些人有没有被处罚、罚到几号——
        // 那是别人的处分信息，只有同组且真能替他报名的人才谈得上看到。
        //
        // 【也必须在锁与事务之内】否则「查过没被限制 → 处罚在这一刻生效 → 提交报名」
        // 这个窗口会让刚生效的处罚漏掉一次；放进来后它与同组校验共用同一条串行化边界。
        //
        // 【用批量版而不是循环单人版】闸门的处置查询是 FOR SHARE 当前读，在 RR 下会连间隙一起锁，
        // 范围可能盖到本批后面的志愿者身上。逐个「锁父行→读处置」会形成
        // 「闸门持 v1 父行 + 覆盖 v5 的间隙 → 等 v5 父行」与「impose(v5) 持 v5 父行 → 等那把间隙」
        // 的环，直接 ER_LOCK_DEADLOCK。批量版先把父行锁按 id 升序全部拿齐再读，不成环。
        sanctionQueryService.assertNoneRestricted(targets, SanctionScope.ACTIVITY, "被代报名");

        LocalDateTime now = LocalDateTime.now();

        Activity activity = activityMapper.selectById(activityId);
        if (activity == null || !Integer.valueOf(STATUS_ACTIVITY_PUBLISHED).equals(activity.getStatus())) {
            throw new BusinessException("活动不存在");
        }
        // 代报名也属于「志愿者端报名」语义：被代的每个人按<b>他自己</b>的身份受开放时间约束，有一个没到就整批不报
        if (notOpenYet(activity, targets, now)) {
            throw new BusinessException("尚未开放报名");
        }
        // 报名截止：留空/脏数据按活动结束时间兜底（与自助报名同口径）
        LocalDateTime enrollDl = activity.getEnrollDeadline() != null ? activity.getEnrollDeadline() : activity.getEndTime();
        if (enrollDl != null && now.isAfter(enrollDl)) {
            throw new BusinessException("报名已截止");
        }

        // slot 一致性 + 项目数量范围（与自助一致）
        List<ActivitySlot> slots = activitySlotMapper.selectList(Wrappers.<ActivitySlot>lambdaQuery()
                .eq(ActivitySlot::getActivityId, activityId)
                .in(ActivitySlot::getId, slotIds));
        if (slots.size() != slotIds.size()) {
            throw new BusinessException("存在无效的时间段");
        }
        int n = slotIds.size();
        Integer min = activity.getMinProjects();
        Integer max = activity.getMaxProjects();
        if (min != null && min > 0 && n < min) {
            throw new BusinessException("至少需报名 " + min + " 个项目");
        }
        if (max != null && n > max) {
            throw new BusinessException("最多可报名 " + max + " 个项目");
        }

        int initStatus = Integer.valueOf(1).equals(activity.getNeedAudit()) ? ENROLL_PENDING : ENROLL_APPROVED;
        int totalInserted = 0;

        // 逐个 target：资格/防重/时段冲突
        for (Long targetId : targets) {
            VolunteerProfileView profile = volunteerQueryService.getProfileForEligibilityForShare(targetId);
            if (profile == null) {
                throw new BusinessException("被代报名同学(id=" + targetId + ")信息异常");
            }
            if (profile.status() == null || profile.status() != USER_STATUS_NORMAL) {
                throw new BusinessException("被代报名同学(id=" + targetId + ")账号状态异常");
            }
            checkEligibility(activity, profile, targetId);

            Long active = enrollmentMapper.selectCount(Wrappers.<ActivityEnrollment>lambdaQuery()
                    .eq(ActivityEnrollment::getActivityId, activityId)
                    .eq(ActivityEnrollment::getVolunteerId, targetId)
                    .in(ActivityEnrollment::getStatus, ENROLL_PENDING, ENROLL_APPROVED));
            if (active != null && active > 0) {
                throw new BusinessException("同学(id=" + targetId + ")已报名该活动");
            }

            checkTimeConflicts(targetId, slots);

            for (ActivitySlot slot : slots) {
                ActivityEnrollment e = new ActivityEnrollment();
                e.setActivityId(activityId);
                e.setSlotId(slot.getId());
                e.setVolunteerId(targetId);
                e.setStatus(initStatus);
                e.setEnrollTime(now);
                e.setProxyByVolunteerId(actorId);
                enrollmentMapper.insert(e);
                totalInserted++;
            }
            notifyProxyEnrolled(activity, slots, targetId, initStatus);
        }
        return totalInserted;
    }

    /**
     * 告诉被代报名的人「你被报上了」（{@code enrollment-position}）。
     *
     * <p><b>为什么只有代报名发、自助报名不发</b>：自己刚点完的事不需要再花一条短信告诉他；
     * 而代报名<b>是别人替他做的决定</b>，他事先并不知情——不通知就可能到了活动当天才发现，
     * 或者压根不知道自己占了一个名额。这条模板的 {@code ${message}} 是自由文案，正好用来说清这件事。</p>
     *
     * <p><b>一个人一条短信，不是一个场次一条</b>：报了三个场次就连发三条内容雷同的短信，
     * 只会让人以为系统坏了。多场次时把岗位名拼在一起。</p>
     *
     * <p>发在事务与分布式锁之内是安全的：{@code SmsNotifyService} 把真正的发送推迟到提交之后，
     * 任何一个 target 校验失败导致整批回滚时，一条短信也不会发出去——而这批代报名本就是全成或全败。</p>
     */
    private void notifyProxyEnrolled(Activity activity, List<ActivitySlot> slots, Long targetId, int initStatus) {
        try {
            String positions = slots.stream()
                    .map(ActivitySlot::getProjectName)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.joining("、"));
            if (!StringUtils.hasText(positions)) {
                positions = activity.getTitle();
            }
            String message = (initStatus == ENROLL_PENDING)
                    ? "已由同小组成员代为报名，等待审核"
                    : "已由同小组成员代为报名成功，请准时参加";
            smsNotifyService.notifyVolunteer(targetId, SmsNotifyTemplate.ENROLLMENT_POSITION,
                    SmsNotifyTemplate.ENROLLMENT_POSITION.params(activity.getTitle(), positions, message));
        } catch (Exception ex) {
            log.error("[SMS-NOTIFY] 代报名通知失败 activityId={} targetId={}", activity.getId(), targetId, ex);
        }
    }

    /**
     * 取消报名：整活动取消，把我对该活动的活跃报名（待审核/已通过）全部置为已取消。
     *
     * @return 取消的报名记录条数
     */
    public int cancel(Long activityId, Long volunteerId) {
        return DistributedLockSupport.runLocked(redissonClient, LOCK_KEY_PREFIX + volunteerId,
                () -> transactionTemplate.execute(s -> doCancel(activityId, volunteerId)));
    }

    private int doCancel(Long activityId, Long volunteerId) {
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException("活动不存在");
        }
        // 仅进行中（已发布）的活动可取消报名；已结束/已取消的活动不应再操作报名（cancelDeadline=null 时尤需此兜底）。
        if (!Integer.valueOf(STATUS_ACTIVITY_PUBLISHED).equals(activity.getStatus())) {
            throw new BusinessException("活动已结束或已取消，无法取消报名");
        }
        // 取消截止：留空/脏数据按活动结束时间兜底（status 不随活动结束改变，否则活动后仍可取消）
        LocalDateTime cancelDl = activity.getCancelDeadline() != null ? activity.getCancelDeadline() : activity.getEndTime();
        if (cancelDl != null && LocalDateTime.now().isAfter(cancelDl)) {
            throw new BusinessException("已过取消报名截止时间");
        }
        List<ActivityEnrollment> active = enrollmentMapper.selectList(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, activityId)
                .eq(ActivityEnrollment::getVolunteerId, volunteerId)
                .in(ActivityEnrollment::getStatus, ENROLL_PENDING, ENROLL_APPROVED));
        if (active.isEmpty()) {
            throw new BusinessException("您未报名该活动");
        }
        for (ActivityEnrollment e : active) {
            e.setStatus(ENROLL_CANCELLED);
            enrollmentMapper.updateById(e);
        }
        return active.size();
    }

    /**
     * 我的报名列表：可选按状态筛选，按报名时间倒序。每条报名记录一行，带出活动与时间段快照。
     */
    public PageResult<MyEnrollmentVO> myEnrollments(PageQuery query, Long volunteerId, Integer status) {
        if (status != null && (status < ENROLL_PENDING || status > ENROLL_CANCELLED)) {
            throw new BusinessException("报名状态取值非法（应为 0~3）");
        }
        Page<ActivityEnrollment> page = query.toPage();
        var wrapper = Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getVolunteerId, volunteerId);
        if (status != null) {
            wrapper.eq(ActivityEnrollment::getStatus, status);
        }
        wrapper.orderByDesc(ActivityEnrollment::getEnrollTime);
        enrollmentMapper.selectPage(page, wrapper);

        List<ActivityEnrollment> records = page.getRecords();
        Map<Long, Activity> activityById = batchLoadActivities(records);
        Map<Long, ActivitySlot> slotById = batchLoadSlots(records);

        List<MyEnrollmentVO> vos = records.stream().map(e -> {
            MyEnrollmentVO vo = new MyEnrollmentVO();
            vo.setEnrollmentId(e.getId());
            vo.setActivityId(e.getActivityId());
            vo.setSlotId(e.getSlotId());
            vo.setStatus(e.getStatus());
            vo.setEnrollTime(e.getEnrollTime());
            vo.setRejectReason(e.getRejectReason());
            Activity a = activityById.get(e.getActivityId());
            if (a != null) {
                vo.setSerialNo(a.getSerialNo());
                vo.setActivityTitle(a.getTitle());
            }
            ActivitySlot slot = slotById.get(e.getSlotId());
            if (slot != null) {
                vo.setProjectName(slot.getProjectName());
                vo.setSlotStartTime(slot.getStartTime());
                vo.setSlotEndTime(slot.getEndTime());
            }
            return vo;
        }).toList();
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }

    // ---------- 内部辅助 ----------

    private void checkEligibility(Activity activity, VolunteerProfileView profile, Long volunteerId) {
        // 指定分队（V4 活动补全批）：只认报名这一刻的归属分队。profile 来自当前读（FOR SHARE），
        // 与「审批加入 / 退出分队」改 volunteer.squad_id 的写入串行，不会拿着刚退队前的快照放行。
        // 后台手动补录（manualEnroll）不走这里，照旧越权。
        if (Integer.valueOf(1).equals(activity.getEnrollScope())
                && (activity.getTargetSquadId() == null || !activity.getTargetSquadId().equals(profile.squadId()))) {
            String squad = activity.getTargetSquadId() == null ? null
                    : squadQueryService.listNamesByIds(List.of(activity.getTargetSquadId())).get(activity.getTargetSquadId());
            throw new BusinessException("本活动仅限「" + (squad == null ? "指定分队" : squad) + "」的成员报名");
        }
        // 年龄
        if (activity.getRequireMinAge() != null || activity.getRequireMaxAge() != null) {
            if (profile.birthday() == null) {
                throw new BusinessException("请先完善实名信息（出生日期）后再报名");
            }
            int age = Period.between(profile.birthday(), LocalDate.now()).getYears();
            if (activity.getRequireMinAge() != null && age < activity.getRequireMinAge()) {
                throw new BusinessException("不满足年龄要求（需 ≥ " + activity.getRequireMinAge() + " 岁）");
            }
            if (activity.getRequireMaxAge() != null && age > activity.getRequireMaxAge()) {
                throw new BusinessException("不满足年龄要求（需 ≤ " + activity.getRequireMaxAge() + " 岁）");
            }
        }
        // 年级
        if (activity.getRequireMinGrade() != null || activity.getRequireMaxGrade() != null) {
            Integer grade = profile.grade();
            if (grade == null) {
                throw new BusinessException("请先完善年级信息后再报名");
            }
            if (activity.getRequireMinGrade() != null && grade < activity.getRequireMinGrade()) {
                throw new BusinessException("不满足年级要求");
            }
            if (activity.getRequireMaxGrade() != null && grade > activity.getRequireMaxGrade()) {
                throw new BusinessException("不满足年级要求");
            }
        }
        // 性别
        if (activity.getRequireGender() != null && !activity.getRequireGender().equals(profile.gender())) {
            throw new BusinessException("本活动有性别要求，您不符合报名条件");
        }
        // 已参加活动「场次」：按不同 activity_id 去重计数（同一活动报多个时间段只算 1 场），
        // 以「已通过」的报名作为 V1「已参加」的近似口径（V1 无签到/时长闭环）。
        Integer minJoin = activity.getRequireMinJoinCount();
        if (minJoin != null && minJoin > 0) {
            long joinedCount = enrollmentMapper.countDistinctJoinedActivities(volunteerId, ENROLL_APPROVED);
            if (joinedCount < minJoin) {
                throw new BusinessException("已参加活动次数不足（需 ≥ " + minJoin + " 次）");
            }
        }
        // 已参加「服务时长」门槛：累计秘书部已确认（secretary_status=1）的 service_minutes 之和须达标。
        Integer minMinutes = activity.getRequireMinJoinMinutes();
        if (minMinutes != null && minMinutes > 0) {
            long confirmedMinutes = attendanceMapper.sumConfirmedMinutes(volunteerId);
            if (confirmedMinutes < minMinutes) {
                throw new BusinessException("已参加服务时长不足（需 ≥ " + minMinutes + " 分钟）");
            }
        }
    }

    /** 选定 slot 彼此不得时间重叠；且不得与我在任何活动的活跃报名 slot 重叠。 */
    private void checkTimeConflicts(Long volunteerId, List<ActivitySlot> newSlots) {
        // 选定 slot 之间
        for (int i = 0; i < newSlots.size(); i++) {
            for (int j = i + 1; j < newSlots.size(); j++) {
                if (overlaps(newSlots.get(i), newSlots.get(j))) {
                    throw new BusinessException("所选时间段之间存在时间冲突");
                }
            }
        }
        // 与已有活跃报名 slot
        List<ActivityEnrollment> myActive = enrollmentMapper.selectList(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getVolunteerId, volunteerId)
                .in(ActivityEnrollment::getStatus, ENROLL_PENDING, ENROLL_APPROVED));
        if (myActive.isEmpty()) {
            return;
        }
        List<Long> activeSlotIds = myActive.stream().map(ActivityEnrollment::getSlotId).distinct().toList();
        List<ActivitySlot> existingSlots = activitySlotMapper.selectBatchIds(activeSlotIds);
        for (ActivitySlot ns : newSlots) {
            for (ActivitySlot es : existingSlots) {
                if (overlaps(ns, es)) {
                    throw new BusinessException("与您已报名的时间段存在时间冲突");
                }
            }
        }
    }

    /** 两个时间段是否重叠（半开区间：仅相接不算冲突）。 */
    private boolean overlaps(ActivitySlot a, ActivitySlot b) {
        return a.getStartTime().isBefore(b.getEndTime()) && b.getStartTime().isBefore(a.getEndTime());
    }

    private Map<Long, Activity> batchLoadActivities(List<ActivityEnrollment> records) {
        List<Long> ids = records.stream().map(ActivityEnrollment::getActivityId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return activityMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Activity::getId, Function.identity()));
    }

    private Map<Long, ActivitySlot> batchLoadSlots(List<ActivityEnrollment> records) {
        List<Long> ids = records.stream().map(ActivityEnrollment::getSlotId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return activitySlotMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(ActivitySlot::getId, Function.identity()));
    }

}
