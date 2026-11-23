package com.hengde.social.dao;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 关注关系（物理行；唯一键 {@code uk_follower_followee} 防重）。
 *
 * @author hengde
 */
@Mapper
public interface SocialFollowMapper {

    @Insert("INSERT INTO social_follow (follower_id, followee_id, create_time) VALUES (#{followerId}, #{followeeId}, NOW())")
    int insert(@Param("followerId") Long followerId, @Param("followeeId") Long followeeId);

    @Delete("DELETE FROM social_follow WHERE follower_id = #{followerId} AND followee_id = #{followeeId}")
    int delete(@Param("followerId") Long followerId, @Param("followeeId") Long followeeId);

    @Select("SELECT COUNT(*) FROM social_follow WHERE follower_id = #{followerId} AND followee_id = #{followeeId}")
    long exists(@Param("followerId") Long followerId, @Param("followeeId") Long followeeId);

    @Select("SELECT COUNT(*) FROM social_follow WHERE followee_id = #{id}")
    long countFollowers(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM social_follow WHERE follower_id = #{id}")
    long countFollowing(@Param("id") Long id);

    /** 粉丝（最近关注的在前）。 */
    @Select("SELECT follower_id FROM social_follow WHERE followee_id = #{id} ORDER BY create_time DESC, id DESC LIMIT #{offset}, #{limit}")
    List<Long> selectFollowerIds(@Param("id") Long id, @Param("offset") long offset, @Param("limit") long limit);

    /** 关注的人（最近关注的在前）。 */
    @Select("SELECT followee_id FROM social_follow WHERE follower_id = #{id} ORDER BY create_time DESC, id DESC LIMIT #{offset}, #{limit}")
    List<Long> selectFollowingIds(@Param("id") Long id, @Param("offset") long offset, @Param("limit") long limit);

    /** 在这批人里，viewer 关注了谁。 */
    @Select("<script>SELECT followee_id FROM social_follow WHERE follower_id = #{viewer} AND followee_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach></script>")
    List<Long> selectFollowedAmong(@Param("viewer") Long viewer, @Param("ids") Collection<Long> ids);
}
