package com.hengde.social.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.social.entity.SocialReport;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 社区举报。 */
public interface SocialReportMapper extends BaseMapper<SocialReport> {

    /** 同一对象上<b>全部</b>待处理的举报一起结案（同一条帖子被十个人举报，处理一次就够）。 */
    @Update("UPDATE social_report SET status = #{status}, handle_action = #{action}, handle_note = #{note}, "
            + "handled_by = #{adminId}, handled_time = NOW() "
            + "WHERE target_type = #{targetType} AND target_id = #{targetId} AND status = 0")
    int resolveTarget(@Param("targetType") int targetType, @Param("targetId") Long targetId, @Param("status") int status,
                      @Param("action") Integer action, @Param("note") String note, @Param("adminId") Long adminId);
}
