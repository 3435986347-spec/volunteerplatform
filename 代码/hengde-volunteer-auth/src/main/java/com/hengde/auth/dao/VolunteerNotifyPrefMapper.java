package com.hengde.auth.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.auth.entity.VolunteerNotifyPref;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 订阅通知偏好。
 *
 * @author hengde
 */
@Mapper
public interface VolunteerNotifyPrefMapper extends BaseMapper<VolunteerNotifyPref> {

    /** 一条语句写入或改写（唯一键 volunteer_id + topic），连点两次不会撞键。 */
    @Update("INSERT INTO volunteer_notify_pref (volunteer_id, topic, sms_enabled, create_time, update_time, is_deleted) "
            + "VALUES (#{volunteerId}, #{topic}, #{smsEnabled}, NOW(), NOW(), 0) "
            + "ON DUPLICATE KEY UPDATE sms_enabled = VALUES(sms_enabled), update_time = NOW()")
    int upsert(@Param("volunteerId") Long volunteerId, @Param("topic") String topic,
               @Param("smsEnabled") int smsEnabled);
}
