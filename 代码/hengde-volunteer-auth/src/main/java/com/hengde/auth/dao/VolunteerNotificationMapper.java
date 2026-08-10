package com.hengde.auth.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.auth.entity.VolunteerNotification;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 志愿者站内提示 Mapper。
 *
 * @author hengde
 */
public interface VolunteerNotificationMapper extends BaseMapper<VolunteerNotification> {

    /**
     * 标记已读（CAS：只动本人的、且仍未读的那一条）。
     *
     * <p><b>为什么带 volunteer_id</b>：不带的话，任何人传一个 id 就能把别人的提示标成已读——
     * 那是一次写别人数据的越权，而且悄无声息（对方只是发现角标自己少了一个）。</p>
     *
     * <p><b>为什么 CAS 未读</b>：{@code read_time} 应当是<b>第一次</b>看到的时刻。
     * 不判的话每刷一次都会把它覆盖成最后一次，这个字段也就没有意义了。</p>
     *
     * @return 1 = 本次确实由未读改成了已读；0 = 不存在、不是本人的、或早已读过
     */
    @Update("UPDATE volunteer_notification SET is_read = 1, read_time = NOW(), update_time = NOW() "
            + "WHERE id = #{id} AND volunteer_id = #{volunteerId} AND is_read = 0 AND is_deleted = 0")
    int markRead(@Param("id") Long id, @Param("volunteerId") Long volunteerId);
}
