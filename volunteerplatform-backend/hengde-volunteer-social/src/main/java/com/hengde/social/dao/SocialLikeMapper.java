package com.hengde.social.dao;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 帖子点赞（物理行；唯一键 {@code uk_post_volunteer} 防重）。
 *
 * @author hengde
 */
@Mapper
public interface SocialLikeMapper {

    /** 重复点赞抛 {@code DuplicateKeyException}，由调用方当作「已经赞过」。 */
    @Insert("INSERT INTO social_post_like (post_id, volunteer_id, create_time) VALUES (#{postId}, #{volunteerId}, NOW())")
    int insert(@Param("postId") Long postId, @Param("volunteerId") Long volunteerId);

    @Delete("DELETE FROM social_post_like WHERE post_id = #{postId} AND volunteer_id = #{volunteerId}")
    int delete(@Param("postId") Long postId, @Param("volunteerId") Long volunteerId);

    @Select("<script>SELECT post_id FROM social_post_like WHERE volunteer_id = #{volunteerId} AND post_id IN "
            + "<foreach collection='postIds' item='i' open='(' separator=',' close=')'>#{i}</foreach></script>")
    List<Long> selectLikedPostIds(@Param("volunteerId") Long volunteerId, @Param("postIds") Collection<Long> postIds);

    @Select("SELECT COUNT(*) FROM social_post_like WHERE post_id = #{postId}")
    long countByPost(@Param("postId") Long postId);
}
