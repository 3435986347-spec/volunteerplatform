package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.constant.ActivityStatus;
import com.hengde.activity.constant.EnrollmentStatus;
import com.hengde.activity.constant.LeaderType;
import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dao.ActivityLeaderMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityEnrollment;
import com.hengde.activity.entity.ActivityLeader;
import com.hengde.activity.vo.ActivityLeaderVO;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerDisplayView;
import com.hengde.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 活动现场负责人指派 / 取消 / 查询，并提供「是否本活动志愿者负责人」判定（供 /v 端鉴权与积分倍率）。
 *
 * <p>负责人后台预设：组织部从报名志愿者中选（leaderType=1，须已有该活动活跃报名），或从管理团队安排
 * （leaderType=2，admin_user.id）。两者均不占活动报名人数。</p>
 *
 * @author hengde
 */
@Service
public class ActivityLeaderService {

    private static final int LEADER_TYPE_VOLUNTEER = LeaderType.VOLUNTEER;
    private static final int LEADER_TYPE_ADMIN = LeaderType.ADMIN;

    private static final int ACTIVITY_PUBLISHED = ActivityStatus.PUBLISHED;
    private static final int ENROLL_PENDING = EnrollmentStatus.PENDING;
    private static final int ENROLL_APPROVED = EnrollmentStatus.APPROVED;

    private ActivityLeaderMapper leaderMapper;
    private ActivityMapper activityMapper;
    private ActivityEnrollmentMapper enrollmentMapper;
    private VolunteerQueryService volunteerQueryService;

    @Autowired
    public void setLeaderMapper(ActivityLeaderMapper leaderMapper) {
        this.leaderMapper = leaderMapper;
    }

    @Autowired
    public void setActivityMapper(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    @Autowired
    public void setEnrollmentMapper(ActivityEnrollmentMapper enrollmentMapper) {
        this.enrollmentMapper = enrollmentMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    /**
     * 指派负责人。leaderType=1 时 refId=volunteer.id（须为本活动报名者）；=2 时 refId=admin_user.id。
     *
     * @return 新建负责人记录 id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long assign(Long activityId, Integer leaderType, Long refId, Long adminId) {
        requireNonReviewActivity(activityId);
        if (refId == null) {
            throw new BusinessException("负责人 id 不能为空");
        }
        ActivityLeader leader = new ActivityLeader();
        leader.setActivityId(activityId);
        leader.setAssignedBy(adminId);
        leader.setAssignedTime(LocalDateTime.now());
        if (Integer.valueOf(LEADER_TYPE_VOLUNTEER).equals(leaderType)) {
            // 负责人来源（两类，均落 leaderType=1 志愿者负责人、用小程序现场签到/统一签退）：
            //  ① 本活动的报名志愿者（活跃报名：待审核/已通过）；
            //  ② 「管理团队」志愿者（manager_flag，可不报名直接安排，不占活动报名人数）。
            // 取 isActiveManager（活跃且 manager_flag=1）口径，与志愿者端 RBAC 一致：取消标记/停用即不可再被指派。
            Long enrolled = enrollmentMapper.selectCount(Wrappers.<ActivityEnrollment>lambdaQuery()
                    .eq(ActivityEnrollment::getActivityId, activityId)
                    .eq(ActivityEnrollment::getVolunteerId, refId)
                    .in(ActivityEnrollment::getStatus, ENROLL_PENDING, ENROLL_APPROVED));
            boolean isEnrolled = enrolled != null && enrolled > 0;
            if (!isEnrolled && !volunteerQueryService.isActiveManager(refId)) {
                throw new BusinessException("只能从本活动报名志愿者或管理团队志愿者中选负责人");
            }
            leader.setLeaderType(LEADER_TYPE_VOLUNTEER);
            leader.setVolunteerId(refId);
        } else if (Integer.valueOf(LEADER_TYPE_ADMIN).equals(leaderType)) {
            leader.setLeaderType(LEADER_TYPE_ADMIN);
            leader.setAdminUserId(refId);
        } else {
            throw new BusinessException("负责人来源非法（1报名志愿者/2管理团队）");
        }
        try {
            leaderMapper.insert(leader);
        } catch (DuplicateKeyException e) {
            // 唯一键 uk_act_vol / uk_act_admin：同一活动重复指派同一人
            throw new BusinessException("该负责人已指派，请勿重复");
        }
        return leader.getId();
    }

    /** 取消指派（按负责人记录 id；校验属于该活动）。 */
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long activityId, Long leaderId) {
        requireNonReviewActivity(activityId);
        ActivityLeader leader = leaderMapper.selectById(leaderId);
        if (leader == null || !leader.getActivityId().equals(activityId)) {
            throw new BusinessException("负责人记录不存在");
        }
        leaderMapper.deleteById(leaderId);
    }

    /** 负责人列表（志愿者负责人带出姓名；管理团队负责人仅出 admin_user.id，名字由 admin 模块自管）。 */
    public List<ActivityLeaderVO> list(Long activityId) {
        requireNonReviewActivity(activityId);
        List<ActivityLeader> leaders = leaderMapper.selectList(Wrappers.<ActivityLeader>lambdaQuery()
                .eq(ActivityLeader::getActivityId, activityId)
                .orderByAsc(ActivityLeader::getId));
        if (leaders.isEmpty()) {
            return List.of();
        }
        List<Long> volunteerIds = leaders.stream()
                .filter(l -> Integer.valueOf(LEADER_TYPE_VOLUNTEER).equals(l.getLeaderType()))
                .map(ActivityLeader::getVolunteerId)
                .filter(java.util.Objects::nonNull)
                .distinct().toList();
        Map<Long, VolunteerDisplayView> displayById = volunteerQueryService.listDisplayByIds(volunteerIds);
        return leaders.stream().map(l -> {
            ActivityLeaderVO vo = new ActivityLeaderVO();
            vo.setId(l.getId());
            vo.setActivityId(l.getActivityId());
            vo.setLeaderType(l.getLeaderType());
            vo.setVolunteerId(l.getVolunteerId());
            vo.setAdminUserId(l.getAdminUserId());
            if (l.getVolunteerId() != null) {
                VolunteerDisplayView d = displayById.get(l.getVolunteerId());
                if (d != null) {
                    vo.setVolunteerName(d.realName());
                }
            }
            vo.setAssignedTime(l.getAssignedTime());
            return vo;
        }).toList();
    }

    /** 该志愿者是否本活动的志愿者负责人（leaderType=1）。供 /v 端鉴权与积分倍率判定。 */
    public boolean isVolunteerLeader(Long activityId, Long volunteerId) {
        Long c = leaderMapper.selectCount(Wrappers.<ActivityLeader>lambdaQuery()
                .eq(ActivityLeader::getActivityId, activityId)
                .eq(ActivityLeader::getLeaderType, LEADER_TYPE_VOLUNTEER)
                .eq(ActivityLeader::getVolunteerId, volunteerId));
        return c != null && c > 0;
    }

    /**
     * 校验该志愿者是本活动的志愿者负责人，否则抛业务异常（/v 端 leader 操作入口用）。
     *
     * <p>先挡审核域活动：即便存在历史/脏 leader 行指向待审/驳回活动，/v 负责人动作也不得触达
     * （否则负责人能在小程序看到/操作未上线活动）。</p>
     */
    public void requireVolunteerLeader(Long activityId, Long volunteerId) {
        requireNonReviewActivity(activityId);
        if (!isVolunteerLeader(activityId, volunteerId)) {
            throw new BusinessException("您不是该活动的负责人");
        }
    }

    /**
     * 活动须存在且非「审核域」（待审核 4/驳回 5）。待审/驳回活动只能经发布审核流程处置，
     * 负责人指派/取消/查看等常规动作不得触达——否则有 {@code activity:leader-assign} 者凭 id 就能
     * 给未上线/已驳回活动安排负责人（leaderType=2 还不受报名校验限制）。
     */
    private Activity requireNonReviewActivity(Long activityId) {
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null || ActivityStatus.isUnderReview(activity.getStatus())) {
            throw new BusinessException("活动不存在");
        }
        return activity;
    }
}
