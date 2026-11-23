package com.hengde.donate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.donate.entity.DonateWishViewer;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 微心愿查看记录。
 *
 * @author hengde
 */
@Mapper
public interface DonateWishViewerMapper extends BaseMapper<DonateWishViewer> {

    /**
     * 记一次查看：一人一个心愿一行，再看累加次数、刷新最近时间与号码。
     * 靠 {@code uk_wish_volunteer} 一条语句完成——先查再插在连点时会撞键。
     */
    @Insert("INSERT INTO donate_wish_viewer (wish_id, volunteer_id, phone, view_count, first_view_time, last_view_time) "
            + "VALUES (#{wishId}, #{volunteerId}, #{phone}, 1, #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE view_count = view_count + 1, last_view_time = VALUES(last_view_time), "
            + "phone = COALESCE(VALUES(phone), phone)")
    int recordView(@Param("wishId") Long wishId, @Param("volunteerId") Long volunteerId,
                   @Param("phone") String phone, @Param("now") LocalDateTime now);
}
