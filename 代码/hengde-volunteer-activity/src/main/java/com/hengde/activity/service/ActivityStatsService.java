package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.constant.ActivityStatus;
import com.hengde.activity.constant.SecretaryStatus;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 活动域平台统计（只读）：供 data 域数据看板聚合「活动场次 / 总服务时长 / 参与人次」。
 *
 * <p>把「哪些状态算一场活动」「时长口径=秘书已确认」「参与=已签到」这些<b>活动域语义</b>收在本服务，
 * 不外泄给 data 域去拼条件（data 只调本服务方法）。逻辑删除由 {@code @TableLogic} 自动排除。</p>
 *
 * @author hengde
 */
@Service
public class ActivityStatsService {

    /**
     * 「真实活动」的 id 子查询：已发布 + 已结束（含历史活动，其为已结束态）。考勤/时长聚合据此过滤，
     * 不依赖上游链路永远干净——草稿/待审/驳回/取消活动上的脏签到/时长不计入看板。
     * is_deleted 手动过滤（子查询走原生 SQL，不经 MP 的 {@code @TableLogic} 自动追加）。
     */
    private static final String REAL_ACTIVITY_IDS =
            "SELECT id FROM activity WHERE status IN (" + ActivityStatus.PUBLISHED + ", "
                    + ActivityStatus.FINISHED + ") AND is_deleted = 0";

    private ActivityMapper activityMapper;
    private ActivityAttendanceMapper attendanceMapper;

    @Autowired
    public void setActivityMapper(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    @Autowired
    public void setAttendanceMapper(ActivityAttendanceMapper attendanceMapper) {
        this.attendanceMapper = attendanceMapper;
    }

    /** 活动场次：已发布 + 已结束（排除草稿/已取消与审核域 4/5）。 */
    public long countActivities() {
        return activityMapper.selectCount(Wrappers.<Activity>lambdaQuery()
                .in(Activity::getStatus, ActivityStatus.PUBLISHED, ActivityStatus.FINISHED));
    }

    /** 参与人次：已发布/已结束活动上有签到记录（check_in_time 非空）的考勤行数。 */
    public long countParticipations() {
        return attendanceMapper.selectCount(Wrappers.<ActivityAttendance>lambdaQuery()
                .isNotNull(ActivityAttendance::getCheckInTime)
                .inSql(ActivityAttendance::getActivityId, REAL_ACTIVITY_IDS));
    }

    /** 总服务时长（分钟）：仅累计已发布/已结束活动上、秘书部已确认（secretary_status=1）的 service_minutes。 */
    public long sumConfirmedServiceMinutes() {
        List<Map<String, Object>> rows = attendanceMapper.selectMaps(new QueryWrapper<ActivityAttendance>()
                .select("IFNULL(SUM(service_minutes),0) AS total")
                .eq("secretary_status", SecretaryStatus.CONFIRMED)
                .inSql("activity_id", REAL_ACTIVITY_IDS));
        if (rows.isEmpty()) {
            return 0L;
        }
        Object total = rows.get(0).get("total");
        return total == null ? 0L : ((Number) total).longValue();
    }
}
