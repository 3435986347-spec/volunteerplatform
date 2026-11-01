package com.hengde.activity.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.activity.entity.ActivityEnrollment;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 活动报名记录 Mapper。
 *
 * @author hengde
 */
public interface ActivityEnrollmentMapper extends BaseMapper<ActivityEnrollment> {

    /**
     * 统计某志愿者「已参加活动场次」——按不同 activity_id 去重，而非报名行数
     * （一人在同一活动报多个时间段会产生多行，但只算 1 场）。口径见需求文档活动发布「已参加活动次数」。
     *
     * @param volunteerId 志愿者 id
     * @param status      报名状态（传已通过=1 作为 V1 「已参加」的近似口径）
     * @return 去重后的活动场次数
     */
    @Select("SELECT COUNT(DISTINCT activity_id) FROM activity_enrollment "
            + "WHERE volunteer_id = #{volunteerId} AND status = #{status} AND is_deleted = 0")
    long countDistinctJoinedActivities(@Param("volunteerId") Long volunteerId, @Param("status") int status);

    /**
     * 各场次已占的名额：待审核和已通过都占，现场负责人不占（{@code activity_leader}）。
     * 与 {@link ActivityMapper} 里 has_quota 同一口径。
     *
     * @param activityId 活动 id
     * @return 每场一行 {slot_id, taken}；没人报的场次不出现
     */
    @Select("""
            SELECT e.slot_id, COUNT(*) AS taken
            FROM activity_enrollment e
            WHERE e.activity_id = #{activityId} AND e.status IN (0, 1) AND e.is_deleted = 0
              AND NOT EXISTS (SELECT 1 FROM activity_leader l
                              WHERE l.activity_id = e.activity_id AND l.volunteer_id = e.volunteer_id
                                AND l.is_deleted = 0)
            GROUP BY e.slot_id
            """)
    List<Map<String, Object>> selectTakenBySlot(@Param("activityId") Long activityId);

    /** {@link #selectTakenBySlot} 转成「场次 id → 已占名额」，没人报的场次取不到。 */
    default Map<Long, Long> takenBySlot(Long activityId) {
        return selectTakenBySlot(activityId).stream().collect(Collectors.toMap(
                row -> ((Number) row.get("slot_id")).longValue(),
                row -> ((Number) row.get("taken")).longValue()));
    }
}
