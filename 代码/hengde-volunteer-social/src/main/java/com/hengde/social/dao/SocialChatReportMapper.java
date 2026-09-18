package com.hengde.social.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.social.entity.SocialChatReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 私聊投诉与关键词工单。
 *
 * @author hengde
 */
@Mapper
public interface SocialChatReportMapper extends BaseMapper<SocialChatReport> {

    /** 待处理队列：关键词工单插队在前（source 2 在前），其余先来先处理。 */
    @Select("<script>SELECT * FROM social_chat_report WHERE 1 = 1"
            + "<if test='status != null'> AND status = #{status}</if>"
            + " ORDER BY status ASC, source DESC, id ASC LIMIT #{offset}, #{limit}</script>")
    List<SocialChatReport> selectQueue(@Param("status") Integer status,
                                       @Param("offset") long offset, @Param("limit") long limit);

    @Select("<script>SELECT COUNT(*) FROM social_chat_report WHERE 1 = 1"
            + "<if test='status != null'> AND status = #{status}</if></script>")
    long countQueue(@Param("status") Integer status);
}
