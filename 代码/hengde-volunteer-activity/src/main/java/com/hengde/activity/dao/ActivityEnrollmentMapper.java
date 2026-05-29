package com.hengde.activity.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.activity.entity.ActivityEnrollment;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
