package com.hengde.social.dao;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 审核设置（单行）与审核记录。
 *
 * @author hengde
 */
@Mapper
public interface SocialReviewMapper {

    @Select("SELECT review_levels FROM social_review_setting WHERE id = 1")
    Integer selectLevels();

    @Update("UPDATE social_review_setting SET review_levels = #{levels}, updated_by = #{adminId}, update_time = NOW() WHERE id = 1")
    int updateLevels(@Param("levels") int levels, @Param("adminId") Long adminId);

    /** 级数调低之后，已经过够新级数的待审帖直接算通过（否则再也没有「下一级」能审它）。 */
    @Update("UPDATE social_post SET review_status = 1, update_time = NOW() "
            + "WHERE review_status = 0 AND is_deleted = 0 AND author_type = 1 AND review_level >= #{levels}")
    int completeReachedLevels(@Param("levels") int levels);

    @Insert("INSERT INTO social_review_log (post_id, level, admin_user_id, action, reason, create_time) "
            + "VALUES (#{postId}, #{level}, #{adminId}, #{action}, #{reason}, NOW())")
    int insertLog(@Param("postId") Long postId, @Param("level") int level, @Param("adminId") Long adminId,
                  @Param("action") int action, @Param("reason") String reason);

    @Select("SELECT COUNT(*) FROM social_review_log WHERE post_id = #{postId} AND admin_user_id = #{adminId} AND action = 1")
    long countApprovalsBy(@Param("postId") Long postId, @Param("adminId") Long adminId);
}
