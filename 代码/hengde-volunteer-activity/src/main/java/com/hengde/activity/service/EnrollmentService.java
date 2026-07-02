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
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerProfileView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.organization.biz.service.GroupQueryService;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
    private GroupQueryService groupQueryService;
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

    /**
     * 报名：选定活动下若干时间段。校验链见方法体；need_audit 决定落库初值（0待审核/1已通过）。
     *
     * @return 实际新增的报名记录条数（= 报名的时间段数）
     */
    public int enroll(Long activityId, List<Long> slotIds, Long volunteerId) {
        // 去重，保留选择顺序
        List<Long> distinctSlotIds = new ArrayList<>(new LinkedHashSet<>(slotIds));
        return DistributedLockSupport.runLocked(redissonClient, LOCK_KEY_PREFIX + volunteerId,
                () -> transactionTemplate.execute(s -> doEnroll(activityId, distinctSlotIds, volunteerId)));
    }

    private int doEnroll(Long activityId, List<Long> slotIds, Long volunteerId) {
        LocalDateTime now = LocalDateTime.now();

        Activity activity = activityMapper.selectById(activityId);
        if (activity == null || !Integer.valueOf(STATUS_ACTIVITY_PUBLISHED).equals(activity.getStatus())) {
            throw new BusinessException("活动不存在");
        }
        if (activity.getEnrollOpenVolunteer() != null && now.isBefore(activity.getEnrollOpenVolunteer())) {
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
        VolunteerProfileView profile = volunteerQueryService.getProfileForEligibility(volunteerId);
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
     * 管理端手动新增报名（管理员代加志愿者，越权补录）。
     *
     * <p>与志愿者自助报名的差异：跳过资格校验（年龄/年级/性别/次数）与报名截止——补录场景常发生在截止后或
     * 不满足资格时；状态直接置为「已通过」并记审核人。但仍保留两条不可越的红线：
     * 同活动防重 + 全平台同时间段冲突（双重占用无意义）。复用同一把「志愿者维度」锁，与该志愿者的自助报名互斥。</p>
     *
     * @return 新增的报名记录条数
     */
    public int manualEnroll(Long activityId, Long volunteerId, List<Long> slotIds, Long adminId) {
        List<Long> distinctSlotIds = new ArrayList<>(new LinkedHashSet<>(slotIds));
        return DistributedLockSupport.runLocked(redissonClient, LOCK_KEY_PREFIX + volunteerId,
                () -> transactionTemplate.execute(s -> doManualEnroll(activityId, distinctSlotIds, volunteerId, adminId)));
    }

    private int doManualEnroll(Long activityId, List<Long> slotIds, Long volunteerId, Long adminId) {
        LocalDateTime now = LocalDateTime.now();

        Activity activity = activityMapper.selectById(activityId);
        if (activity == null || !Integer.valueOf(STATUS_ACTIVITY_PUBLISHED).equals(activity.getStatus())) {
            throw new BusinessException("活动不存在");
        }
        // 越权仅跳过「资格条件」（年龄/年级/性别/次数/截止）；账号存在与「未被禁用」不是资格条件，仍须拦截。
        VolunteerProfileView profile = volunteerQueryService.getProfileForEligibility(volunteerId);
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

        LocalDateTime now = LocalDateTime.now();

        Activity activity = activityMapper.selectById(activityId);
        if (activity == null || !Integer.valueOf(STATUS_ACTIVITY_PUBLISHED).equals(activity.getStatus())) {
            throw new BusinessException("活动不存在");
        }
        // 代报名也属于「志愿者端报名」语义，被代者按志愿者角色受同一道开放时间约束
        if (activity.getEnrollOpenVolunteer() != null && now.isBefore(activity.getEnrollOpenVolunteer())) {
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
            VolunteerProfileView profile = volunteerQueryService.getProfileForEligibility(targetId);
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
        }
        return totalInserted;
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
