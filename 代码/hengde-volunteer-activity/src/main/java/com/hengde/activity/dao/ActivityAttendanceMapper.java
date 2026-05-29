package com.hengde.activity.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.activity.entity.ActivityAttendance;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 活动考勤/服务记录 Mapper。
 *
 * @author hengde
 */
public interface ActivityAttendanceMapper extends BaseMapper<ActivityAttendance> {

    /**
     * 统计某志愿者「已确认服务总时长」（分钟）——仅秘书部已确认(secretary_status=1)的记录计入，
     * 作为报名「已参加时长门槛」的口径（第 2 批 eligibility 用）。
     *
     * @param volunteerId 志愿者 id
     * @return 已确认服务总分钟数（无记录返回 0）
     */
    @Select("SELECT COALESCE(SUM(service_minutes), 0) FROM activity_attendance "
            + "WHERE volunteer_id = #{volunteerId} AND secretary_status = 1 AND is_deleted = 0")
    long sumConfirmedMinutes(@Param("volunteerId") Long volunteerId);
}
