package com.hengde.social.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.constant.SanctionScope;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.social.constant.SocialCodes;
import com.hengde.social.dao.SocialCommentMapper;
import com.hengde.social.dao.SocialPostMapper;
import com.hengde.social.dto.SocialDTOs;
import com.hengde.social.entity.SocialComment;
import com.hengde.social.entity.SocialPost;
import com.hengde.social.vo.SocialVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 评论 / 回复（Row 23「长按自己的评论可以删除，发布人可以长按删除、举报他人评论」；举报属治理批）。
 *
 * <p>官方身份的评论（后台「回复、评论」）只能落在官方帖下，删除按部门——见 {@link #officialComment} / {@link #officialDelete}。</p>
 *
 * @author hengde
 */
@Service
public class SocialCommentService {

    private SocialCommentMapper commentMapper;
    private SocialPostMapper postMapper;
    private SocialPostService postService;
    private SocialGateService gate;
    private SocialCardService cardService;
    private HotRankService hotRankService;
    private SocialInteractionFeedService feedService;

    @Autowired
    public void setFeedService(SocialInteractionFeedService feedService) {
        this.feedService = feedService;
    }
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setCommentMapper(SocialCommentMapper commentMapper) {
        this.commentMapper = commentMapper;
    }

    @Autowired
    public void setPostMapper(SocialPostMapper postMapper) {
        this.postMapper = postMapper;
    }

    @Autowired
    public void setPostService(SocialPostService postService) {
        this.postService = postService;
    }

    @Autowired
    public void setGate(SocialGateService gate) {
        this.gate = gate;
    }

    @Autowired
    public void setCardService(SocialCardService cardService) {
        this.cardService = cardService;
    }

    @Autowired
    public void setHotRankService(HotRankService hotRankService) {
        this.hotRankService = hotRankService;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ================= 志愿者 =================

    public Long comment(Long viewer, Long postId, SocialDTOs.CommentSave dto) {
        gate.requireActor(viewer);
        String content = requireContent(dto);
        Long id = transactionTemplate.execute(s -> {
            gate.assertNotRestricted(viewer, SanctionScope.COMMUNITY_COMMENT, "评论");
            SocialPost p = postService.requireVisible(viewer, postId);
            if (!postService.commentable(p, postService.authorSetting(p))) {
                throw new BusinessException("这条帖子不允许评论");
            }
            SocialComment c = newComment(postId, content, dto.getParentId());
            c.setAuthorType(SocialCodes.AUTHOR_VOLUNTEER);
            c.setAuthorId(viewer);
            commentMapper.insert(c);
            postMapper.incComment(postId);
            feedService.commented(viewer, p, c);
            return c.getId();
        });
        hotRankService.bumpComment(postId);
        return id;
    }

    /** 删评论：评论是自己的，或者（志愿者帖）帖子是自己的。 */
    public void delete(Long viewer, Long commentId) {
        SocialComment c = commentMapper.selectById(commentId);
        SocialPost p = c == null ? null : postMapper.selectById(c.getPostId());
        boolean ownComment = c != null && Integer.valueOf(SocialCodes.AUTHOR_VOLUNTEER).equals(c.getAuthorType())
                && Objects.equals(c.getAuthorId(), viewer);
        boolean ownPost = p != null && Integer.valueOf(SocialCodes.AUTHOR_VOLUNTEER).equals(p.getAuthorType())
                && Objects.equals(p.getAuthorId(), viewer);
        if (!ownComment && !ownPost) {
            throw new BusinessException("评论不存在");
        }
        softDelete(c, SocialCodes.AUTHOR_VOLUNTEER, viewer);
    }

    public PageResult<SocialVOs.Comment> list(Long viewer, Long postId, PageQuery query) {
        gate.requireViewer(viewer);
        SocialPost p = postService.requireVisible(viewer, postId);
        return page(viewer, p, query);
    }

    /** TA 发过的评论（只列我看得到的帖子下的）。 */
    public PageResult<SocialVOs.Comment> userComments(Long viewer, Long userId, PageQuery query) {
        gate.requireViewer(viewer);
        cardService.requireCard(userId);
        long offset = (long) (query.getPage() - 1) * query.getSize();
        List<SocialComment> rows = commentMapper.selectVisibleByAuthor(viewer, userId, offset, query.getSize());
        long total = commentMapper.countVisibleByAuthor(viewer, userId);
        Map<Long, SocialPost> posts = new java.util.HashMap<>();
        if (!rows.isEmpty()) {
            postMapper.selectBatchIds(rows.stream().map(SocialComment::getPostId).distinct().toList())
                    .forEach(x -> posts.put(x.getId(), x));
        }
        return PageResult.of(toVOs(viewer, rows, posts), total, query.getPage(), query.getSize());
    }

    // ================= 官方（后台） =================

    public Long officialComment(Long adminId, String department, Long postId, SocialDTOs.CommentSave dto) {
        if (!StringUtils.hasText(department)) {
            throw new BusinessException("后台账号没有填写部门，不能以官方身份评论");
        }
        String content = requireContent(dto);
        SocialPost p = requireOfficialPost(postId);
        SocialComment c = newComment(p.getId(), content, dto.getParentId());
        c.setAuthorType(SocialCodes.AUTHOR_OFFICIAL);
        c.setAuthorId(adminId);
        c.setAuthorDepartment(department);
        transactionTemplate.executeWithoutResult(s -> {
            commentMapper.insert(c);
            postMapper.incComment(p.getId());
        });
        return c.getId();
    }

    /** 删官方帖下的评论：本部门的官方帖，或持有全部门权限（{@code department} 传 null）。 */
    public void officialDelete(Long adminId, String department, Long commentId) {
        SocialComment c = commentMapper.selectById(commentId);
        SocialPost p = c == null ? null : postMapper.selectById(c.getPostId());
        if (p == null || !Integer.valueOf(SocialCodes.AUTHOR_OFFICIAL).equals(p.getAuthorType())
                || (department != null && !department.equals(p.getOfficialDepartment()))) {
            throw new BusinessException("评论不存在");
        }
        softDelete(c, SocialCodes.AUTHOR_OFFICIAL, adminId);
    }

    public PageResult<SocialVOs.Comment> officialList(Long postId, PageQuery query) {
        return page(null, requireOfficialPost(postId), query);
    }

    // ================= 内部 =================

    private PageResult<SocialVOs.Comment> page(Long viewer, SocialPost post, PageQuery query) {
        long offset = (long) (query.getPage() - 1) * query.getSize();
        List<SocialComment> rows = commentMapper.selectList(Wrappers.<SocialComment>lambdaQuery()
                .eq(SocialComment::getPostId, post.getId())
                .orderByAsc(SocialComment::getId)
                .last("LIMIT " + offset + ", " + query.getSize()));
        Long total = commentMapper.selectCount(Wrappers.<SocialComment>lambdaQuery().eq(SocialComment::getPostId, post.getId()));
        return PageResult.of(toVOs(viewer, rows, Map.of(post.getId(), post)), total == null ? 0 : total, query.getPage(), query.getSize());
    }

    private void softDelete(SocialComment c, int byType, Long by) {
        transactionTemplate.executeWithoutResult(s -> {
            if (commentMapper.softDelete(c.getId(), byType, by) == 1) {
                postMapper.decComment(c.getPostId());
            }
        });
    }

    private SocialPost requireOfficialPost(Long postId) {
        SocialPost p = postId == null ? null : postMapper.selectById(postId);
        if (p == null || !Integer.valueOf(SocialCodes.AUTHOR_OFFICIAL).equals(p.getAuthorType())) {
            throw new BusinessException("官方帖不存在");
        }
        return p;
    }

    private SocialComment newComment(Long postId, String content, Long parentId) {
        SocialComment c = new SocialComment();
        c.setPostId(postId);
        c.setContent(content);
        if (parentId != null) {
            SocialComment parent = commentMapper.selectById(parentId);
            if (parent == null || !postId.equals(parent.getPostId())) {
                throw new BusinessException("回复的评论不存在");
            }
            c.setParentId(parentId);
            c.setReplyToAuthorType(parent.getAuthorType());
            c.setReplyToAuthorId(parent.getAuthorId());
        }
        return c;
    }

    private static String requireContent(SocialDTOs.CommentSave dto) {
        String content = dto == null || dto.getContent() == null ? "" : dto.getContent().trim();
        if (content.isEmpty()) {
            throw new BusinessException("请填写评论内容");
        }
        if (content.length() > SocialCodes.MAX_COMMENT) {
            throw new BusinessException("评论不超过 " + SocialCodes.MAX_COMMENT + " 字");
        }
        return content;
    }

    private List<SocialVOs.Comment> toVOs(Long viewer, List<SocialComment> rows, Map<Long, SocialPost> posts) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> volunteerIds = new HashSet<>();
        for (SocialComment c : rows) {
            if (Integer.valueOf(SocialCodes.AUTHOR_VOLUNTEER).equals(c.getAuthorType())) {
                volunteerIds.add(c.getAuthorId());
            }
            if (Integer.valueOf(SocialCodes.AUTHOR_VOLUNTEER).equals(c.getReplyToAuthorType())) {
                volunteerIds.add(c.getReplyToAuthorId());
            }
        }
        Map<Long, SocialVOs.Author> cards = cardService.volunteers(volunteerIds);
        List<SocialVOs.Comment> out = new ArrayList<>();
        for (SocialComment c : rows) {
            SocialVOs.Comment vo = new SocialVOs.Comment();
            vo.setId(c.getId());
            vo.setPostId(c.getPostId());
            vo.setAuthor(author(c.getAuthorType(), c.getAuthorId(), c.getAuthorDepartment(), cards));
            vo.setParentId(c.getParentId());
            if (c.getReplyToAuthorType() != null) {
                vo.setReplyTo(author(c.getReplyToAuthorType(), c.getReplyToAuthorId(), null, cards));
            }
            vo.setContent(c.getContent());
            SocialPost p = posts.get(c.getPostId());
            boolean ownComment = Integer.valueOf(SocialCodes.AUTHOR_VOLUNTEER).equals(c.getAuthorType())
                    && viewer != null && viewer.equals(c.getAuthorId());
            boolean ownPost = p != null && Integer.valueOf(SocialCodes.AUTHOR_VOLUNTEER).equals(p.getAuthorType())
                    && viewer != null && viewer.equals(p.getAuthorId());
            vo.setDeletable(ownComment || ownPost);
            vo.setCreateTime(c.getCreateTime());
            out.add(vo);
        }
        return out;
    }

    private SocialVOs.Author author(Integer type, Long id, String department, Map<Long, SocialVOs.Author> cards) {
        if (Integer.valueOf(SocialCodes.AUTHOR_OFFICIAL).equals(type)) {
            return cardService.official(department);
        }
        return cards.get(id);
    }
}
