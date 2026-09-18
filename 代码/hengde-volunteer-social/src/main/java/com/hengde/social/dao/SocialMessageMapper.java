package com.hengde.social.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.social.entity.SocialMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 私信消息。
 *
 * @author hengde
 */
@Mapper
public interface SocialMessageMapper extends BaseMapper<SocialMessage> {

    /** 志愿者看：比自己的清空水位新的、没被后台删的，新的在前（游标 beforeId，第一页传 0）。 */
    @Select("<script>SELECT * FROM social_message WHERE conversation_id = #{conversationId} AND is_deleted = 0 "
            + "AND id &gt; #{clearedId}<if test='beforeId != null and beforeId &gt; 0'> AND id &lt; #{beforeId}</if>"
            + " ORDER BY id DESC LIMIT #{limit}</script>")
    List<SocialMessage> selectForVolunteer(@Param("conversationId") Long conversationId, @Param("clearedId") long clearedId,
                                           @Param("beforeId") Long beforeId, @Param("limit") long limit);

    /** 后台看：全部，含双方各自清空的（Row 23 F 要的就是这个），被删的也列出来但标出来。 */
    @Select("SELECT * FROM social_message WHERE conversation_id = #{conversationId} ORDER BY id DESC LIMIT #{offset}, #{limit}")
    List<SocialMessage> selectForAdmin(@Param("conversationId") Long conversationId,
                                       @Param("offset") long offset, @Param("limit") long limit);

    @Select("SELECT COUNT(*) FROM social_message WHERE conversation_id = #{conversationId}")
    long countForAdmin(@Param("conversationId") Long conversationId);

    /** 后台删违规消息（显式软删 SQL：@TableLogic 列不能经 wrapper set）。 */
    @Update("UPDATE social_message SET is_deleted = 1, update_time = NOW() WHERE id = #{id} AND is_deleted = 0")
    int softDelete(@Param("id") Long id);

    /** 这条会话里这个人发过几条（「对方没回之前最多 3 条」的兜底查询，会话上的计数是快照）。 */
    @Select("SELECT COUNT(*) FROM social_message WHERE conversation_id = #{conversationId} AND sender_id = #{senderId}")
    long countBySender(@Param("conversationId") Long conversationId, @Param("senderId") Long senderId);
}
