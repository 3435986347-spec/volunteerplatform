package com.hengde.social.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.social.entity.SocialPost;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * 社区帖子。
 *
 * <p><b>「谁看得到这条帖子」只写在 {@link #VISIBLE} 一处</b>：帖子流、搜索、TA的主页、帖子详情、评论列表的前置判断、
 * 主页发帖量全都拼它。分成两份（列表一份 SQL、详情一份 Java）迟早会出现「列表里没有、按 id 却打得开」。</p>
 *
 * <p>规则：删除的看不到；官方帖所有人看得到（后台隐藏的除外）；自己的帖子自己都看得到（隐藏 / 驳回 / 待审的都看得到）；别人的帖子——
 * <b>后台隐藏的、审核驳回的、命中关键词还没审完的</b>（先藏后审，D7）看不到，被作者「不让TA看」的看不到，
 * 其余按帖子可见性：0 不限制 / 1 隐藏（只有作者）/ 2 作者关注的人可看 / 3 关注作者的人可看。</p>
 *
 * @author hengde
 */
public interface SocialPostMapper extends BaseMapper<SocialPost> {

    String VISIBLE = " p.is_deleted = 0 AND ("
            + " (p.author_type = 2 AND p.admin_hidden = 0)"
            // 企业帖（V4 爱心企业批）：公开、照样过后台隐藏 / 审核驳回 / 关键词先藏后审；企业没有关注与「不让TA看」，所以没有可见性分档
            + " OR (p.author_type = 3 AND p.admin_hidden = 0 AND p.review_status != 2"
            + "     AND NOT (p.keyword_hit = 1 AND p.review_status = 0))"
            + " OR (p.author_type = 1 AND p.author_id = #{viewer})"
            + " OR (p.author_type = 1 AND p.admin_hidden = 0 AND p.review_status != 2"
            + "     AND NOT (p.keyword_hit = 1 AND p.review_status = 0)"
            + "     AND NOT EXISTS (SELECT 1 FROM social_block b WHERE b.owner_id = p.author_id AND b.target_id = #{viewer})"
            + "     AND (p.visibility = 0"
            + "          OR (p.visibility = 2 AND EXISTS (SELECT 1 FROM social_follow f"
            + "                 WHERE f.follower_id = p.author_id AND f.followee_id = #{viewer}))"
            + "          OR (p.visibility = 3 AND EXISTS (SELECT 1 FROM social_follow f"
            + "                 WHERE f.follower_id = #{viewer} AND f.followee_id = p.author_id)))))";

    String FILTERS = "<if test='tab == \"official\"'> AND p.author_type = 2</if>"
            + "<if test='tab == \"following\"'> AND p.author_type = 1 AND p.author_id IN"
            + " (SELECT f2.followee_id FROM social_follow f2 WHERE f2.follower_id = #{viewer})</if>"
            + "<if test='authorId != null'> AND p.author_type = 1 AND p.author_id = #{authorId}</if>"
            + "<if test='keyword != null'> AND (p.content LIKE CONCAT('%', #{keyword}, '%')"
            + " OR p.official_label LIKE CONCAT('%', #{keyword}, '%'))</if>"
            + "<if test='ids != null'> AND p.id IN <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach></if>";

    /** 帖子流 / 搜索 / TA的主页：按发布时间倒序，手写分页（领域模块测试上下文没有分页拦截器）。 */
    @Select("<script>SELECT p.* FROM social_post p WHERE " + VISIBLE + FILTERS
            + " ORDER BY <if test='tab == \"latest\" or tab == \"official\"'>p.pinned DESC, p.pin_time DESC, </if>"
            + "p.create_time DESC, p.id DESC LIMIT #{offset}, #{limit}</script>")
    List<SocialPost> selectVisible(@Param("viewer") Long viewer, @Param("tab") String tab, @Param("authorId") Long authorId,
                                   @Param("keyword") String keyword, @Param("ids") Collection<Long> ids,
                                   @Param("offset") long offset, @Param("limit") long limit);

    @Select("<script>SELECT COUNT(*) FROM social_post p WHERE " + VISIBLE + FILTERS + "</script>")
    long countVisible(@Param("viewer") Long viewer, @Param("tab") String tab, @Param("authorId") Long authorId,
                      @Param("keyword") String keyword, @Param("ids") Collection<Long> ids);

    /** 这个人看得到这条帖子吗（详情、评论、点赞、分享都先过它）。 */
    @Select("SELECT p.* FROM social_post p WHERE p.id = #{id} AND " + VISIBLE)
    SocialPost selectVisibleById(@Param("viewer") Long viewer, @Param("id") Long id);

    /** 企业删自己的帖子（deleted_by_type 3＝企业本人）。 */
    @Update("UPDATE social_post SET is_deleted = 1, deleted_by_type = 3, deleted_by = #{enterpriseId}, update_time = NOW() "
            + "WHERE id = #{id} AND author_type = 3 AND author_id = #{enterpriseId} AND is_deleted = 0")
    int softDeleteByEnterprise(@Param("id") Long id, @Param("enterpriseId") Long enterpriseId);

    /** 企业主页的帖子（企业帖只有「看得到 / 看不到」，没有关注关系那一套）。 */
    @Select("<script>SELECT p.* FROM social_post p WHERE " + VISIBLE
            + " AND p.author_type = 3 AND p.author_id = #{enterpriseId}"
            + " ORDER BY p.create_time DESC, p.id DESC LIMIT #{offset}, #{limit}</script>")
    List<SocialPost> selectEnterpriseVisible(@Param("viewer") Long viewer, @Param("enterpriseId") Long enterpriseId,
                                             @Param("offset") long offset, @Param("limit") long limit);

    @Select("<script>SELECT COUNT(*) FROM social_post p WHERE " + VISIBLE
            + " AND p.author_type = 3 AND p.author_id = #{enterpriseId}</script>")
    long countEnterpriseVisible(@Param("viewer") Long viewer, @Param("enterpriseId") Long enterpriseId);

    /** 作者收到的点赞总数（主页「点赞量」，只算没删的帖子）。 */
    @Select("SELECT COALESCE(SUM(like_count), 0) FROM social_post WHERE author_type = 1 AND author_id = #{authorId} AND is_deleted = 0")
    long sumLikesOfAuthor(@Param("authorId") Long authorId);

    @Update("UPDATE social_post SET is_deleted = 1, deleted_by_type = 1, deleted_by = #{volunteerId}, update_time = NOW() "
            + "WHERE id = #{id} AND author_type = 1 AND author_id = #{volunteerId} AND is_deleted = 0")
    int softDeleteByAuthor(@Param("id") Long id, @Param("volunteerId") Long volunteerId);

    /** 后台删任何帖子（社区治理批）。 */
    @Update("UPDATE social_post SET is_deleted = 1, deleted_by_type = 2, deleted_by = #{adminId}, update_time = NOW() "
            + "WHERE id = #{id} AND is_deleted = 0")
    int softDeleteByAdmin(@Param("id") Long id, @Param("adminId") Long adminId);

    /** 删官方帖；{@code department} 为 null＝不限部门（持有 {@code social:official-all}）。 */
    @Update("<script>UPDATE social_post SET is_deleted = 1, deleted_by_type = 2, deleted_by = #{adminId}, update_time = NOW() "
            + "WHERE id = #{id} AND author_type = 2 AND is_deleted = 0"
            + "<if test='department != null'> AND official_department = #{department}</if></script>")
    int softDeleteOfficial(@Param("id") Long id, @Param("adminId") Long adminId, @Param("department") String department);

    @Update("UPDATE social_post SET like_count = like_count + 1 WHERE id = #{id} AND is_deleted = 0")
    int incLike(@Param("id") Long id);

    @Update("UPDATE social_post SET like_count = like_count - 1 WHERE id = #{id} AND like_count > 0")
    int decLike(@Param("id") Long id);

    @Update("UPDATE social_post SET comment_count = comment_count + 1 WHERE id = #{id} AND is_deleted = 0")
    int incComment(@Param("id") Long id);

    @Update("UPDATE social_post SET comment_count = comment_count - 1 WHERE id = #{id} AND comment_count > 0")
    int decComment(@Param("id") Long id);

    @Update("UPDATE social_post SET view_count = view_count + 1 WHERE id = #{id} AND is_deleted = 0")
    int incView(@Param("id") Long id);

    @Update("UPDATE social_post SET share_count = share_count + 1 WHERE id = #{id} AND is_deleted = 0")
    int incShare(@Param("id") Long id);
}
