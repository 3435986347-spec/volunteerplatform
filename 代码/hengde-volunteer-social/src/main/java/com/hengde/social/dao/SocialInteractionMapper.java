package com.hengde.social.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.social.entity.SocialInteraction;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 社区互动 + 汇总提示水位。
 *
 * @author hengde
 */
public interface SocialInteractionMapper extends BaseMapper<SocialInteraction> {

    @Update("UPDATE social_interaction SET is_read = 1 WHERE recipient_id = #{recipientId} AND is_read = 0 AND is_deleted = 0")
    int markAllRead(@Param("recipientId") Long recipientId);

    /** 需要汇总提示的人：有未读、且比上次汇总更新的互动（一次最多 {@code limit} 人）。 */
    @Select("SELECT i.recipient_id AS recipientId, COALESCE(MAX(d.last_digest_id), 0) AS lastId, MAX(i.id) AS maxId, COUNT(*) AS cnt "
            + "FROM social_interaction i LEFT JOIN social_interaction_digest d ON d.volunteer_id = i.recipient_id "
            + "WHERE i.is_deleted = 0 AND i.is_read = 0 AND i.id > COALESCE(d.last_digest_id, 0) "
            + "GROUP BY i.recipient_id ORDER BY i.recipient_id LIMIT #{limit}")
    List<Map<String, Object>> selectDigestCandidates(@Param("limit") int limit);

    @Insert("INSERT IGNORE INTO social_interaction_digest (volunteer_id, last_digest_id, update_time) VALUES (#{volunteerId}, 0, NOW())")
    int ensureDigestRow(@Param("volunteerId") Long volunteerId);

    /** 水位 CAS：只有还停在 {@code lastId} 时才推进——同一段互动只提示一次。 */
    @Update("UPDATE social_interaction_digest SET last_digest_id = #{maxId}, update_time = NOW() "
            + "WHERE volunteer_id = #{volunteerId} AND last_digest_id = #{lastId}")
    int advanceDigest(@Param("volunteerId") Long volunteerId, @Param("lastId") long lastId, @Param("maxId") long maxId);
}
