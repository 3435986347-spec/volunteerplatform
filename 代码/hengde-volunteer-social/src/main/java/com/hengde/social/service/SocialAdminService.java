package com.hengde.social.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerDisplayView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.social.constant.SocialCodes;
import com.hengde.social.constant.SocialGovCodes;
import com.hengde.social.dao.SocialCommentMapper;
import com.hengde.social.dao.SocialPostMapper;
import com.hengde.social.dao.SocialReviewMapper;
import com.hengde.social.entity.SocialComment;
import com.hengde.social.entity.SocialPost;
import com.hengde.social.vo.SocialGovVOs;
import com.hengde.social.vo.SocialVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台社区管理（Row 23 F「管理、禁言、隐藏、删除、发布、置顶；需要两个界面，按照时间排序显示帖子、评论；
 * 最高权限管理员才可以看到发布人的真实姓名和学校」）。
 *
 * <p><b>真实姓名与学校只有持 {@code social:real-name} 才下发</b>（默认不授任何人、超管通配），由控制器按权限传 {@code withRealName}。</p>
 *
 * @author hengde
 */
@Service
public class SocialAdminService {

    private SocialPostMapper postMapper;
    private SocialCommentMapper commentMapper;
    private SocialReviewMapper reviewMapper;
    private SocialCardService cardService;
    private SocialMediaService mediaService;
    private VolunteerQueryService volunteerQueryService;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setPostMapper(SocialPostMapper postMapper) {
        this.postMapper = postMapper;
    }

    @Autowired
    public void setCommentMapper(SocialCommentMapper commentMapper) {
        this.commentMapper = commentMapper;
    }

    @Autowired
    public void setReviewMapper(SocialReviewMapper reviewMapper) {
        this.reviewMapper = reviewMapper;
    }

    @Autowired
    public void setCardService(SocialCardService cardService) {
        this.cardService = cardService;
    }

    @Autowired
    public void setMediaService(SocialMediaService mediaService) {
        this.mediaService = mediaService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 帖子列表（按发布时间倒序）。筛选：审核状态、是否命中关键词、是否隐藏、作者、正文关键字。 */
    public PageResult<SocialGovVOs.AdminPost> posts(Integer reviewStatus, Boolean keywordHit, Boolean hidden, Long authorId,
                                                    String keyword, boolean withRealName, PageQuery query) {
        var wrapper = Wrappers.<SocialPost>lambdaQuery()
                .eq(reviewStatus != null, SocialPost::getReviewStatus, reviewStatus)
                .eq(keywordHit != null, SocialPost::getKeywordHit, Boolean.TRUE.equals(keywordHit) ? 1 : 0)
                .eq(hidden != null, SocialPost::getAdminHidden, Boolean.TRUE.equals(hidden) ? 1 : 0)
                .eq(authorId != null, SocialPost::getAuthorType, SocialCodes.AUTHOR_VOLUNTEER)
                .eq(authorId != null, SocialPost::getAuthorId, authorId)
                .like(StringUtils.hasText(keyword), SocialPost::getContent, keyword == null ? null : keyword.trim())
                .orderByDesc(SocialPost::getCreateTime).orderByDesc(SocialPost::getId);
        Long total = postMapper.selectCount(wrapper);
        long offset = (long) (query.getPage() - 1) * query.getSize();
        List<SocialPost> rows = postMapper.selectList(wrapper.last("LIMIT " + offset + ", " + query.getSize()));
        return PageResult.of(toAdminPosts(rows, withRealName), total == null ? 0 : total, query.getPage(), query.getSize());
    }

    /** 评论列表（按时间倒序）；可按帖子、作者、内容关键字筛。 */
    public PageResult<SocialGovVOs.AdminComment> comments(Long postId, Long authorId, String keyword, boolean withRealName, PageQuery query) {
        var wrapper = Wrappers.<SocialComment>lambdaQuery()
                .eq(postId != null, SocialComment::getPostId, postId)
                .eq(authorId != null, SocialComment::getAuthorType, SocialCodes.AUTHOR_VOLUNTEER)
                .eq(authorId != null, SocialComment::getAuthorId, authorId)
                .like(StringUtils.hasText(keyword), SocialComment::getContent, keyword == null ? null : keyword.trim())
                .orderByDesc(SocialComment::getId);
        Long total = commentMapper.selectCount(wrapper);
        long offset = (long) (query.getPage() - 1) * query.getSize();
        List<SocialComment> rows = commentMapper.selectList(wrapper.last("LIMIT " + offset + ", " + query.getSize()));
        Set<Long> volunteerIds = new HashSet<>();
        rows.stream().filter(c -> Integer.valueOf(SocialCodes.AUTHOR_VOLUNTEER).equals(c.getAuthorType()))
                .forEach(c -> volunteerIds.add(c.getAuthorId()));
        Map<Long, SocialVOs.Author> cards = cardService.volunteers(volunteerIds);
        Map<Long, VolunteerDisplayView> real = withRealName && !volunteerIds.isEmpty()
                ? volunteerQueryService.listDisplayByIds(volunteerIds) : Map.of();
        List<SocialGovVOs.AdminComment> out = rows.stream().map(c -> {
            SocialGovVOs.AdminComment vo = new SocialGovVOs.AdminComment();
            boolean official = Integer.valueOf(SocialCodes.AUTHOR_OFFICIAL).equals(c.getAuthorType());
            vo.setId(c.getId());
            vo.setPostId(c.getPostId());
            vo.setAuthor(official ? cardService.official(c.getAuthorDepartment()) : cards.get(c.getAuthorId()));
            VolunteerDisplayView d = official ? null : real.get(c.getAuthorId());
            vo.setRealName(d == null ? null : d.realName());
            vo.setSchool(d == null ? null : d.school());
            vo.setContent(c.getContent());
            vo.setParentId(c.getParentId());
            vo.setCreateTime(c.getCreateTime());
            return vo;
        }).toList();
        return PageResult.of(out, total == null ? 0 : total, query.getPage(), query.getSize());
    }

    public void setHidden(Long postId, boolean hidden) {
        if (postMapper.update(null, Wrappers.<SocialPost>lambdaUpdate()
                .eq(SocialPost::getId, postId)
                .set(SocialPost::getAdminHidden, hidden ? 1 : 0)
                .set(SocialPost::getUpdateTime, LocalDateTime.now())) == 0) {
            throw new BusinessException("帖子不存在");
        }
    }

    public void setPinned(Long postId, boolean pinned) {
        if (postMapper.update(null, Wrappers.<SocialPost>lambdaUpdate()
                .eq(SocialPost::getId, postId)
                .set(SocialPost::getPinned, pinned ? 1 : 0)
                .set(SocialPost::getPinTime, pinned ? LocalDateTime.now() : null)
                .set(SocialPost::getUpdateTime, LocalDateTime.now())) == 0) {
            throw new BusinessException("帖子不存在");
        }
    }

    /** 后台删任何帖子。 */
    public void deletePost(Long adminId, Long postId) {
        if (postMapper.softDeleteByAdmin(postId, adminId) == 0) {
            throw new BusinessException("帖子不存在");
        }
    }

    /** 后台删任何评论（CAS 成功才减帖子的评论数）。 */
    public void deleteComment(Long adminId, Long commentId) {
        SocialComment c = commentId == null ? null : commentMapper.selectById(commentId);
        if (c == null) {
            throw new BusinessException("评论不存在");
        }
        transactionTemplate.executeWithoutResult(s -> {
            if (commentMapper.softDelete(commentId, SocialCodes.AUTHOR_OFFICIAL, adminId) == 1) {
                postMapper.decComment(c.getPostId());
            }
        });
    }

    List<SocialGovVOs.AdminPost> toAdminPosts(List<SocialPost> rows, boolean withRealName) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> volunteerIds = new HashSet<>();
        rows.stream().filter(p -> Integer.valueOf(SocialCodes.AUTHOR_VOLUNTEER).equals(p.getAuthorType()))
                .forEach(p -> volunteerIds.add(p.getAuthorId()));
        Map<Long, SocialVOs.Author> cards = cardService.volunteers(volunteerIds);
        Map<Long, VolunteerDisplayView> real = withRealName && !volunteerIds.isEmpty()
                ? volunteerQueryService.listDisplayByIds(volunteerIds) : Map.of();
        Integer levels = reviewMapper.selectLevels();
        return rows.stream().map(p -> {
            boolean official = Integer.valueOf(SocialCodes.AUTHOR_OFFICIAL).equals(p.getAuthorType());
            // 企业帖的 author_id 是企业 id：拿它去 cards / real 里取，会取到 id 恰好相同的那个志愿者（连真实姓名一起）
            boolean volunteerPost = Integer.valueOf(SocialCodes.AUTHOR_VOLUNTEER).equals(p.getAuthorType());
            SocialGovVOs.AdminPost vo = new SocialGovVOs.AdminPost();
            vo.setId(p.getId());
            vo.setAuthor(official ? cardService.official(p.getOfficialDepartment())
                    : volunteerPost ? cards.get(p.getAuthorId())
                    : cardService.enterprise(p.getAuthorSnapshotName(), p.getAuthorSnapshotAvatar()));
            VolunteerDisplayView d = volunteerPost ? real.get(p.getAuthorId()) : null;
            vo.setRealName(d == null ? null : d.realName());
            vo.setSchool(d == null ? null : d.school());
            vo.setOfficialLabel(p.getOfficialLabel());
            vo.setContent(p.getContent());
            vo.setMediaType(p.getMediaType());
            vo.setMediaUrls(mediaService.fromJson(p.getMediaUrls()));
            vo.setVisibility(p.getVisibility());
            vo.setReviewStatus(p.getReviewStatus());
            vo.setReviewStatusLabel(SocialGovCodes.reviewLabel(p.getReviewStatus()));
            vo.setReviewLevel(p.getReviewLevel());
            vo.setReviewLevels(levels == null ? 1 : levels);
            vo.setKeywordHit(Integer.valueOf(1).equals(p.getKeywordHit()));
            vo.setKeywordHits(p.getKeywordHits());
            vo.setAdminHidden(Integer.valueOf(1).equals(p.getAdminHidden()));
            vo.setPinned(Integer.valueOf(1).equals(p.getPinned()));
            vo.setViewCount(p.getViewCount());
            vo.setLikeCount(p.getLikeCount());
            vo.setCommentCount(p.getCommentCount());
            vo.setShareCount(p.getShareCount());
            vo.setCreateTime(p.getCreateTime());
            vo.setEditTime(p.getEditTime());
            return vo;
        }).toList();
    }
}
