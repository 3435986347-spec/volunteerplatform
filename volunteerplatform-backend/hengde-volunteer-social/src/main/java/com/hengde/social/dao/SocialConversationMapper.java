package com.hengde.social.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.social.entity.SocialConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 私信会话。
 *
 * <p><b>未读数、已发条数、最后一条全部用「读改写合一」的 UPDATE 语句</b>（{@code SET x = x + 1}）：
 * 先查出来再写回去会在并发对发时互相覆盖，而私信恰恰是两个人同时在打字。</p>
 *
 * @author hengde
 */
@Mapper
public interface SocialConversationMapper extends BaseMapper<SocialConversation> {

    @Select("SELECT * FROM social_conversation WHERE small_id = #{small} AND large_id = #{large}")
    SocialConversation selectByPair(@Param("small") Long small, @Param("large") Long large);

    /**
     * 当前读 + 排他锁，发消息的事务第一条语句。
     *
     * <p><b>承重</b>：陌生人限额是「先数一数再决定发不发」，同一个人连点时那几次数到的都是并发提交之前的快照，
     * 8 条同时发能成 5 条（用例当场撞出来的）。锁住会话这一行，同一条会话里的发送就串行了，
     * 计数与随后的 {@code applySent} 之间没有窗口。用排他锁是因为读完之后就要改这一行。</p>
     */
    @Select("SELECT * FROM social_conversation WHERE id = #{id} FOR UPDATE")
    SocialConversation selectByIdForUpdate(@Param("id") Long id);

    /**
     * 建会话：撞唯一键就什么也不做（并发对发的两条第一消息只该建出一条会话）。
     *
     * <p>用 {@code INSERT IGNORE} 而不是「先查再插」——后者在并发下必然有一方插不进去还以为自己建成了。</p>
     */
    @Update("INSERT IGNORE INTO social_conversation (small_id, large_id, create_time, update_time) "
            + "VALUES (#{small}, #{large}, NOW(), NOW())")
    int insertIgnore(@Param("small") Long small, @Param("large") Long large);

    /** 记下最后一条消息：发送人那一侧已发数 +1，接收人那一侧未读 +1。 */
    @Update("<script>UPDATE social_conversation SET last_message_id = #{messageId}, last_content = #{summary}, "
            + "last_sender_id = #{senderId}, last_time = #{time}, update_time = NOW(), "
            + "<if test='senderIsSmall'>small_sent = small_sent + 1, large_unread = large_unread + 1</if>"
            + "<if test='!senderIsSmall'>large_sent = large_sent + 1, small_unread = small_unread + 1</if>"
            + " WHERE id = #{id}</script>")
    int applySent(@Param("id") Long id, @Param("messageId") Long messageId, @Param("summary") String summary,
                  @Param("senderId") Long senderId, @Param("time") java.time.LocalDateTime time,
                  @Param("senderIsSmall") boolean senderIsSmall);

    /** 我读了这条会话：只清我这一侧的未读。 */
    @Update("<script>UPDATE social_conversation SET update_time = NOW(), "
            + "<if test='small'>small_unread = 0</if><if test='!small'>large_unread = 0</if>"
            + " WHERE id = #{id}</script>")
    int clearUnread(@Param("id") Long id, @Param("small") boolean small);

    /** 清空聊天记录：只抬高我这一侧的水位（后台记录一条不少），顺带清我的未读。 */
    @Update("<script>UPDATE social_conversation SET update_time = NOW(), "
            + "<if test='small'>small_cleared_id = GREATEST(small_cleared_id, #{waterline}), small_unread = 0</if>"
            + "<if test='!small'>large_cleared_id = GREATEST(large_cleared_id, #{waterline}), large_unread = 0</if>"
            + " WHERE id = #{id}</script>")
    int clearHistory(@Param("id") Long id, @Param("small") boolean small, @Param("waterline") long waterline);

    /** 我的会话列表：清空之后没有新消息的不出现（水位 >= 最后一条）。 */
    @Select("SELECT * FROM social_conversation WHERE (small_id = #{me} OR large_id = #{me}) "
            + "AND last_message_id IS NOT NULL "
            + "AND ((small_id = #{me} AND small_cleared_id < last_message_id) OR (large_id = #{me} AND large_cleared_id < last_message_id)) "
            + "ORDER BY last_time DESC, id DESC LIMIT #{offset}, #{limit}")
    List<SocialConversation> selectMine(@Param("me") Long me, @Param("offset") long offset, @Param("limit") long limit);

    @Select("SELECT COUNT(*) FROM social_conversation WHERE (small_id = #{me} OR large_id = #{me}) "
            + "AND last_message_id IS NOT NULL "
            + "AND ((small_id = #{me} AND small_cleared_id < last_message_id) OR (large_id = #{me} AND large_cleared_id < last_message_id))")
    long countMine(@Param("me") Long me);

    /** 未读总数（小程序角标）。 */
    @Select("SELECT COALESCE(SUM(CASE WHEN small_id = #{me} THEN small_unread ELSE large_unread END), 0) "
            + "FROM social_conversation WHERE small_id = #{me} OR large_id = #{me}")
    long totalUnread(@Param("me") Long me);

    /** 后台按人查会话（不传就是全部）。 */
    @Select("<script>SELECT * FROM social_conversation WHERE last_message_id IS NOT NULL"
            + "<if test='volunteerId != null'> AND (small_id = #{volunteerId} OR large_id = #{volunteerId})</if>"
            + " ORDER BY last_time DESC, id DESC LIMIT #{offset}, #{limit}</script>")
    List<SocialConversation> selectForAdmin(@Param("volunteerId") Long volunteerId,
                                            @Param("offset") long offset, @Param("limit") long limit);

    @Select("<script>SELECT COUNT(*) FROM social_conversation WHERE last_message_id IS NOT NULL"
            + "<if test='volunteerId != null'> AND (small_id = #{volunteerId} OR large_id = #{volunteerId})</if></script>")
    long countForAdmin(@Param("volunteerId") Long volunteerId);
}
