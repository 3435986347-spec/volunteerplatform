package com.hengde.user.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.user.entity.VolunteerCard;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 志愿者证（V76）。
 *
 * @author hengde
 */
public interface VolunteerCardMapper extends BaseMapper<VolunteerCard> {

    /** 换一个新令牌：旧码当场失效。按唯一键 {@code uk_volunteer} 改那一行（调用方先保证这一行存在）。 */
    @Update("UPDATE user_volunteer_card SET token = #{token}, issue_time = #{now}, reset_count = reset_count + 1, update_time = #{now} "
            + "WHERE volunteer_id = #{volunteerId}")
    int resetToken(@Param("volunteerId") Long volunteerId, @Param("token") String token, @Param("now") LocalDateTime now);
}
