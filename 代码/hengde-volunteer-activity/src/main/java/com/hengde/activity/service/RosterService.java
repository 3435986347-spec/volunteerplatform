package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengde.activity.constant.ActivityStatus;
import com.hengde.activity.constant.EnrollmentStatus;
import com.hengde.activity.constant.LeaderType;
import com.hengde.activity.constant.RunStatus;
import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dao.ActivityLeaderMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityEnrollment;
import com.hengde.activity.entity.ActivityLeader;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.vo.RosterVO;
import com.hengde.auth.service.AdminQueryService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerContactView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.utils.MaskUtil;
import com.hengde.organization.exam.service.TempLeaderQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 活动名单公示（V4 活动补全批，xlsx Row 13）。
 *
 * <p><b>公示窗口</b>：「公示显示时间为组织部确认名单到活动开始，活动开始后则不再显示」——组织部点「确认名单」写
 * {@code roster_publish_time}；「活动开始」<b>按时间现算不落库</b>（{@code start_time} 已过，或负责人提前点了开始），
 * 不靠定时任务去撤——漏跑一次，已经开始的活动名单就一直挂着。已取消 / 已结束 / 历史活动一律不显示。</p>
 *
 * <p><b>名单是实时的，不是确认那一刻的快照</b>：确认之后组织部再审过 / 删掉的人即时反映——公示要的是「现在谁去」，
 * 快照会让被删掉的人继续挂在公示上。</p>
 *
 * <p><b>电话</b>：普通志愿者中间打 *、活动负责人全显示（原文）；但<b>查看的人是游客时负责人也打 *</b>——
 * 游客收个验证码就能登录，给游客下发全号等于把负责人电话公开（与组织架构那条同一个判断）。后台预览全显示。</p>
 *
 * <p><b>排序</b>：活动按开始时间，时间段按开始时间；段内管理团队在前（Row 13 F「优先展示管理团队」），其余按报名先后。
 * 「考试通过的临时负责人」优先要等临时负责人考试批有了资格表再接。</p>
 *
 * @author hengde
 */
@Service
public class RosterService {

    private ActivityMapper activityMapper;
    private ActivitySlotMapper slotMapper;
    private ActivityEnrollmentMapper enrollmentMapper;
    private ActivityLeaderMapper leaderMapper;
    private VolunteerQueryService volunteerQueryService;
    private AdminQueryService adminQueryService;
    private TempLeaderQueryService tempLeaderQueryService;

    @Autowired
    public void setTempLeaderQueryService(TempLeaderQueryService tempLeaderQueryService) {
        this.tempLeaderQueryService = tempLeaderQueryService;
    }

    @Autowired
    public void setActivityMapper(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    @Autowired
    public void setSlotMapper(ActivitySlotMapper slotMapper) {
        this.slotMapper = slotMapper;
    }

    @Autowired
    public void setEnrollmentMapper(ActivityEnrollmentMapper enrollmentMapper) {
        this.enrollmentMapper = enrollmentMapper;
    }

    @Autowired
    public void setLeaderMapper(ActivityLeaderMapper leaderMapper) {
        this.leaderMapper = leaderMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setAdminQueryService(AdminQueryService adminQueryService) {
        this.adminQueryService = adminQueryService;
    }

    // ================= 后台 =================

    /**
     * 组织部确认名单、开始公示。只有已发布、还没开始的普通活动可以；已公示的再点不报错（不刷新时间——公示从第一次确认算起）。
     */
    public void publish(Long activityId, Long adminId) {
        if (adminId == null) {
            throw new IllegalArgumentException("adminId 不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        int n = activityMapper.update(null, Wrappers.<Activity>lambdaUpdate()
                .eq(Activity::getId, activityId)
                .eq(Activity::getStatus, ActivityStatus.PUBLISHED)
                .ne(Activity::getIsHistorical, 1)
                .eq(Activity::getRunStatus, RunStatus.NOT_STARTED)
                .gt(Activity::getStartTime, now)
                .isNull(Activity::getRosterPublishTime)
                .set(Activity::getRosterPublishTime, now)
                .set(Activity::getRosterPublishBy, adminId)
                .set(Activity::getUpdateTime, now));
        if (n == 1) {
            return;
        }
        Activity a = activityMapper.selectById(activityId);
        if (a == null || ActivityStatus.isUnderReview(a.getStatus())) {
            throw new BusinessException("活动不存在");
        }
        if (!visibleWindow(a, now)) {
            throw new BusinessException("只有已发布、还没开始的活动才能公示名单");
        }
        // 走到这里只剩「已经公示过」：幂等
    }

    /** 撤回公示（名单确认错了、要重新审）。没公示过的再点不报错。 */
    public void withdraw(Long activityId) {
        int n = activityMapper.update(null, Wrappers.<Activity>lambdaUpdate()
                .eq(Activity::getId, activityId)
                .isNotNull(Activity::getRosterPublishTime)
                .set(Activity::getRosterPublishTime, null)
                .set(Activity::getRosterPublishBy, null)
                .set(Activity::getUpdateTime, LocalDateTime.now()));
        if (n == 0) {
            Activity a = activityMapper.selectById(activityId);
            if (a == null || ActivityStatus.isUnderReview(a.getStatus())) {
                throw new BusinessException("活动不存在");
            }
        }
    }

    /** 后台预览：不看公示窗口，电话全显示。 */
    public RosterVO previewForAdmin(Long activityId) {
        Activity a = activityMapper.selectById(activityId);
        if (a == null || ActivityStatus.isUnderReview(a.getStatus())) {
            throw new BusinessException("活动不存在");
        }
        return build(List.of(a), true, true).get(0);
    }

    // ================= 志愿者端 =================

    /**
     * 正在公示的名单（按活动开始时间）。
     *
     * @param registeredViewer 查看的人是否已实名——决定负责人电话是否全显示
     */
    public PageResult<RosterVO> listForVolunteer(PageQuery query, boolean registeredViewer) {
        LocalDateTime now = LocalDateTime.now();
        Page<Activity> page = activityMapper.selectPage(new Page<>(query.getPage(), query.getSize()),
                visibleQuery(now).orderByAsc(Activity::getStartTime).orderByAsc(Activity::getId));
        List<RosterVO> records = page.getRecords().isEmpty() ? List.of() : build(page.getRecords(), registeredViewer, false);
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 某个活动正在公示的名单；不在公示窗口内（没确认 / 已开始 / 已取消）一律「没有公示」。 */
    public RosterVO detailForVolunteer(Long activityId, boolean registeredViewer) {
        Activity a = activityMapper.selectOne(visibleQuery(LocalDateTime.now()).eq(Activity::getId, activityId));
        if (a == null) {
            throw new BusinessException("该活动没有正在公示的名单");
        }
        return build(List.of(a), registeredViewer, false).get(0);
    }

    // ================= 内部 =================

    private static com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Activity> visibleQuery(LocalDateTime now) {
        return Wrappers.<Activity>lambdaQuery()
                .eq(Activity::getStatus, ActivityStatus.PUBLISHED)
                .ne(Activity::getIsHistorical, 1)
                .eq(Activity::getRunStatus, RunStatus.NOT_STARTED)
                .gt(Activity::getStartTime, now)
                .isNotNull(Activity::getRosterPublishTime);
    }

    private static boolean visibleWindow(Activity a, LocalDateTime now) {
        return Integer.valueOf(ActivityStatus.PUBLISHED).equals(a.getStatus())
                && !Integer.valueOf(1).equals(a.getIsHistorical())
                && Integer.valueOf(RunStatus.NOT_STARTED).equals(a.getRunStatus())
                && a.getStartTime() != null && a.getStartTime().isAfter(now);
    }

    /**
     * 一批活动的名单一次拼完：时间段、已通过报名、负责人、联系方式、管理团队标记各查一次。
     *
     * @param fullLeaderPhone 负责人电话是否全显示
     * @param fullPhone       所有人电话都全显示（后台预览）
     */
    private List<RosterVO> build(List<Activity> activities, boolean fullLeaderPhone, boolean fullPhone) {
        List<Long> activityIds = activities.stream().map(Activity::getId).toList();
        Map<Long, List<ActivitySlot>> slotsByActivity = slotMapper.selectList(Wrappers.<ActivitySlot>lambdaQuery()
                        .in(ActivitySlot::getActivityId, activityIds)
                        .orderByAsc(ActivitySlot::getStartTime)
                        .orderByAsc(ActivitySlot::getId))
                .stream().collect(Collectors.groupingBy(ActivitySlot::getActivityId, LinkedHashMap::new, Collectors.toList()));
        List<ActivityEnrollment> enrollments = enrollmentMapper.selectList(Wrappers.<ActivityEnrollment>lambdaQuery()
                .in(ActivityEnrollment::getActivityId, activityIds)
                .eq(ActivityEnrollment::getStatus, EnrollmentStatus.APPROVED)
                .orderByAsc(ActivityEnrollment::getEnrollTime)
                .orderByAsc(ActivityEnrollment::getId));
        List<ActivityLeader> leaders = leaderMapper.selectList(Wrappers.<ActivityLeader>lambdaQuery()
                .in(ActivityLeader::getActivityId, activityIds)
                .orderByAsc(ActivityLeader::getId));

        Set<Long> volunteerIds = new HashSet<>();
        enrollments.forEach(e -> volunteerIds.add(e.getVolunteerId()));
        leaders.stream().map(ActivityLeader::getVolunteerId).filter(Objects::nonNull).forEach(volunteerIds::add);
        Map<Long, VolunteerContactView> contacts = volunteerQueryService.listContactsByIds(volunteerIds);
        Set<Long> managers = volunteerQueryService.filterManagers(volunteerIds);
        Set<Long> tempLeaders = tempLeaderQueryService.filterTempLeaders(volunteerIds);
        List<Long> adminIds = leaders.stream()
                .filter(l -> Integer.valueOf(LeaderType.ADMIN).equals(l.getLeaderType()))
                .map(ActivityLeader::getAdminUserId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> adminNames = adminQueryService.listNamesByIds(adminIds);
        Map<Long, String> adminPhones = adminQueryService.listPhonesByIds(adminIds);

        Map<Long, Set<Long>> volunteerLeadersByActivity = new HashMap<>();
        Map<Long, List<RosterVO.Person>> leaderPeople = new HashMap<>();
        for (ActivityLeader l : leaders) {
            RosterVO.Person p = new RosterVO.Person();
            p.setLeader(true);
            if (Integer.valueOf(LeaderType.VOLUNTEER).equals(l.getLeaderType()) && l.getVolunteerId() != null) {
                volunteerLeadersByActivity.computeIfAbsent(l.getActivityId(), k -> new HashSet<>()).add(l.getVolunteerId());
                VolunteerContactView c = contacts.get(l.getVolunteerId());
                p.setName(c == null ? null : c.realName());
                p.setPhone(phone(c == null ? null : c.phone(), fullPhone || fullLeaderPhone));
                p.setManager(managers.contains(l.getVolunteerId()));
                p.setTempLeader(tempLeaders.contains(l.getVolunteerId()));
            } else {
                p.setName(adminNames.get(l.getAdminUserId()));
                p.setPhone(phone(adminPhones.get(l.getAdminUserId()), fullPhone || fullLeaderPhone));
            }
            leaderPeople.computeIfAbsent(l.getActivityId(), k -> new ArrayList<>()).add(p);
        }

        Map<Long, List<ActivityEnrollment>> enrollmentsBySlot = enrollments.stream()
                .collect(Collectors.groupingBy(ActivityEnrollment::getSlotId, LinkedHashMap::new, Collectors.toList()));
        List<RosterVO> out = new ArrayList<>();
        for (Activity a : activities) {
            RosterVO vo = new RosterVO();
            vo.setActivityId(a.getId());
            vo.setSerialNo(a.getSerialNo());
            vo.setTitle(a.getTitle());
            vo.setLocation(a.getLocation());
            vo.setStartTime(a.getStartTime());
            vo.setEndTime(a.getEndTime());
            vo.setRosterPublishTime(a.getRosterPublishTime());
            vo.setLeaders(leaderPeople.getOrDefault(a.getId(), List.of()));
            Set<Long> activityLeaders = volunteerLeadersByActivity.getOrDefault(a.getId(), Set.of());
            for (ActivitySlot s : slotsByActivity.getOrDefault(a.getId(), List.of())) {
                RosterVO.Slot slot = new RosterVO.Slot();
                slot.setSlotId(s.getId());
                slot.setProjectName(s.getProjectName());
                slot.setStartTime(s.getStartTime());
                slot.setEndTime(s.getEndTime());
                slot.setNeedCount(s.getNeedCount());
                List<ActivityEnrollment> rows = new ArrayList<>(enrollmentsBySlot.getOrDefault(s.getId(), List.of()));
                // 管理团队在前、考试通过的活动临时负责人其次（Row 13 F）；同组内保持报名先后（查询已按报名时间、id 排好，sort 是稳定的）
                rows.sort(Comparator.comparingInt((ActivityEnrollment e) ->
                        managers.contains(e.getVolunteerId()) ? 0 : tempLeaders.contains(e.getVolunteerId()) ? 1 : 2));
                for (ActivityEnrollment e : rows) {
                    VolunteerContactView c = contacts.get(e.getVolunteerId());
                    RosterVO.Person p = new RosterVO.Person();
                    boolean leader = activityLeaders.contains(e.getVolunteerId());
                    p.setName(c == null ? null : c.realName());
                    p.setLeader(leader);
                    p.setManager(managers.contains(e.getVolunteerId()));
                    p.setTempLeader(tempLeaders.contains(e.getVolunteerId()));
                    p.setPhone(phone(c == null ? null : c.phone(), fullPhone || (leader && fullLeaderPhone)));
                    slot.getMembers().add(p);
                }
                vo.getSlots().add(slot);
            }
            out.add(vo);
        }
        return out;
    }

    private static String phone(String phone, boolean full) {
        return phone == null ? null : full ? phone : MaskUtil.maskPhone(phone);
    }
}
