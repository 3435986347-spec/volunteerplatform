package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

    private static final int LEADER_TYPE_VOLUNTEER = 1;
    private static final int LEADER_TYPE_ADMIN = 2;

    private static final int ACTIVITY_PUBLISHED = 1;
    private static final int ENROLL_PENDING = 0;
    private static final int ENROLL_APPROVED = 1;

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
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException("活动不存在");
        }
        if (refId == null) {
            throw new BusinessException("负责人 id 不能为空");
        }
        ActivityLeader leader = new ActivityLeader();
        leader.setActivityId(activityId);
        leader.setAssignedBy(adminId);
        leader.setAssignedTime(LocalDateTime.now());
        if (Integer.valueOf(LEADER_TYPE_VOLUNTEER).equals(leaderType)) {
            // 必须是本活动的报名志愿者（活跃报名：待审核/已通过）
            Long enrolled = enrollmentMapper.selectCount(Wrappers.<ActivityEnrollment>lambdaQuery()
                    .eq(ActivityEnrollment::getActivityId, activityId)
                    .eq(ActivityEnrollment::getVolunteerId, refId)
                    .in(ActivityEnrollment::getStatus, ENROLL_PENDING, ENROLL_APPROVED));
            if (enrolled == null || enrolled == 0) {
                throw new BusinessException("只能从本活动报名志愿者中选负责人");
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
        ActivityLeader leader = leaderMapper.selectById(leaderId);
        if (leader == null || !leader.getActivityId().equals(activityId)) {
            throw new BusinessException("负责人记录不存在");
        }
        leaderMapper.deleteById(leaderId);
    }

    /** 负责人列表（志愿者负责人带出姓名；管理团队负责人仅出 admin_user.id，名字由 admin 模块自管）。 */
    public List<ActivityLeaderVO> list(Long activityId) {
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

    /** 校验该志愿者是本活动的志愿者负责人，否则抛业务异常（/v 端 leader 操作入口用）。 */
    public void requireVolunteerLeader(Long activityId, Long volunteerId) {
        if (!isVolunteerLeader(activityId, volunteerId)) {
            throw new BusinessException("您不是该活动的负责人");
        }
    }
}
