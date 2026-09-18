package com.hengde.social.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.social.entity.SocialComment;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 帖子评论。
 *
 * @author hengde
 */
public interface SocialCommentMapper extends BaseMapper<SocialComment> {

    /** 删评论（CAS：只动还没删的，影响行数为 1 才减帖子的评论数）。 */
    @Update("UPDATE social_comment SET is_deleted = 1, deleted_by_type = #{byType}, deleted_by = #{by}, update_time = NOW() "
            + "WHERE id = #{id} AND is_deleted = 0")
    int softDelete(@Param("id") Long id, @Param("byType") int byType, @Param("by") Long by);

    /** 某人发过的评论，只列 viewer 看得到的帖子下的（帖子可见性口径见 {@link SocialPostMapper#VISIBLE}）。 */
    @Select("SELECT c.* FROM social_comment c JOIN social_post p ON p.id = c.post_id"
            + " WHERE c.is_deleted = 0 AND c.author_type = 1 AND c.author_id = #{authorId} AND " + SocialPostMapper.VISIBLE
            + " ORDER BY c.id DESC LIMIT #{offset}, #{limit}")
    List<SocialComment> selectVisibleByAuthor(@Param("viewer") Long viewer, @Param("authorId") Long authorId,
                                              @Param("offset") long offset, @Param("limit") long limit);

    @Select("SELECT COUNT(*) FROM social_comment c JOIN social_post p ON p.id = c.post_id"
            + " WHERE c.is_deleted = 0 AND c.author_type = 1 AND c.author_id = #{authorId} AND " + SocialPostMapper.VISIBLE)
    long countVisibleByAuthor(@Param("viewer") Long viewer, @Param("authorId") Long authorId);
}
