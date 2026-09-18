package com.hengde.social.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.entity.VolunteerNotification;
import com.hengde.auth.service.NotificationService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.social.constant.SocialCodes;
import com.hengde.social.constant.SocialGovCodes;
import com.hengde.social.dao.SocialCommentMapper;
import com.hengde.social.dao.SocialInteractionMapper;
import com.hengde.social.dao.SocialPostMapper;
import com.hengde.social.entity.SocialComment;
import com.hengde.social.entity.SocialInteraction;
import com.hengde.social.entity.SocialPost;
import com.hengde.social.vo.SocialGovVOs;
import com.hengde.social.vo.SocialVOs;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 互动（Row 23「互动：展示他人评论（回复）自己帖子的评论和时间，点击可以跳转，长按可以删除、举报」）+ 每 20 分钟的汇总提示（Row 23 D）。
 *
 * <p><b>记录</b>：赞了我的帖子 / 评论了我的帖子 / 回复了我的评论 / 关注了我，与那个动作<b>同一事务</b>写入（站内记录能跟着回滚，判据同站内提示）。
 * 自己对自己不记；点赞与关注按「谁对谁的哪条」去重，反复点赞取消只记一条。</p>
 *
 * <p><b>汇总提示</b>：Row 23 D 原文是「订阅消息」，那要协会报备微信订阅消息模板（Q13）；报备之前落<b>站内提示</b>
 * （{@link VolunteerNotification#TYPE_SOCIAL_INTERACTIONS}）。每人一条水位，<b>CAS 推进水位成功才发提示</b>，同一段互动只提示一次；
 * 整个任务再上一把全局锁，多实例 / 重叠触发时只有一个在跑。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class SocialInteractionFeedService {

    static final String DIGEST_LOCK = "lock:social:interaction-digest";

    private SocialInteractionMapper interactionMapper;
    private SocialPostMapper postMapper;
    private SocialCommentMapper commentMapper;
    private SocialCardService cardService;
    private NotificationService notificationService;
    private RedissonClient redissonClient;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setInteractionMapper(SocialInteractionMapper interactionMapper) {
        this.interactionMapper = interactionMapper;
    }

    @Autowired
    public void setPostMapper(SocialPostMapper postMapper) {
        this.postMapper = postMapper;
    }

    @Autowired
    public void setCommentMapper(SocialCommentMapper commentMapper) {
        this.commentMapper = commentMapper;
    }

    @Autowired
    public void setCardService(SocialCardService cardService) {
        this.cardService = cardService;
    }

    @Autowired
    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ================= 记录（由点赞 / 评论 / 关注在各自的事务里调） =================

    public void liked(Long actorId, SocialPost post) {
        if (Integer.valueOf(SocialCodes.AUTHOR_VOLUNTEER).equals(post.getAuthorType())) {
            record(post.getAuthorId(), actorId, SocialGovCodes.INTERACT_LIKE, post.getId(), null);
        }
    }

    public void commented(Long actorId, SocialPost post, SocialComment comment) {
        Long replyTo = Integer.valueOf(SocialCodes.AUTHOR_VOLUNTEER).equals(comment.getReplyToAuthorType())
                ? comment.getReplyToAuthorId() : null;
        if (replyTo != null) {
            record(replyTo, actorId, SocialGovCodes.INTERACT_REPLY, post.getId(), comment.getId());
        }
        if (Integer.valueOf(SocialCodes.AUTHOR_VOLUNTEER).equals(post.getAuthorType()) && !Objects.equals(post.getAuthorId(), replyTo)) {
            record(post.getAuthorId(), actorId, SocialGovCodes.INTERACT_COMMENT, post.getId(), comment.getId());
        }
    }

    public void followed(Long actorId, Long targetId) {
        record(targetId, actorId, SocialGovCodes.INTERACT_FOLLOW, null, null);
    }

    private void record(Long recipientId, Long actorId, int type, Long postId, Long commentId) {
        if (recipientId == null || Objects.equals(recipientId, actorId)) {
            return;
        }
        SocialInteraction i = new SocialInteraction();
        i.setRecipientId(recipientId);
        i.setActorId(actorId);
        i.setType(type);
        i.setPostId(postId);
        i.setCommentId(commentId);
        i.setIsRead(0);
        i.setCreateTime(LocalDateTime.now());
        try {
            interactionMapper.insert(i);
        } catch (DuplicateKeyException ignored) {
            // 点赞 / 关注去重：同一个人反复点，对方只收到一条
        }
    }

    // ================= 志愿者看 =================

    public PageResult<SocialGovVOs.Interaction> list(Long viewer, PageQuery query) {
        var wrapper = Wrappers.<SocialInteraction>lambdaQuery()
                .eq(SocialInteraction::getRecipientId, viewer)
                .orderByDesc(SocialInteraction::getId);
        Long total = interactionMapper.selectCount(wrapper);
        long offset = (long) (query.getPage() - 1) * query.getSize();
        List<SocialInteraction> rows = interactionMapper.selectList(wrapper.last("LIMIT " + offset + ", " + query.getSize()));
        Map<Long, SocialVOs.Author> actors = cardService.volunteers(rows.stream().map(SocialInteraction::getActorId).toList());
        Map<Long, SocialPost> posts = new HashMap<>();
        rows.stream().map(SocialInteraction::getPostId).filter(Objects::nonNull).distinct()
                .forEach(id -> {
                    SocialPost p = postMapper.selectVisibleById(viewer, id);
                    if (p != null) {
                        posts.put(id, p);
                    }
                });
        Map<Long, SocialComment> comments = new HashMap<>();
        List<Long> commentIds = rows.stream().map(SocialInteraction::getCommentId).filter(Objects::nonNull).distinct().toList();
        if (!commentIds.isEmpty()) {
            commentMapper.selectBatchIds(commentIds).forEach(c -> comments.put(c.getId(), c));
        }
        List<SocialGovVOs.Interaction> out = rows.stream().map(i -> {
            SocialGovVOs.Interaction vo = new SocialGovVOs.Interaction();
            vo.setId(i.getId());
            vo.setType(i.getType());
            vo.setTypeLabel(SocialGovCodes.interactionLabel(i.getType()));
            vo.setActor(actors.get(i.getActorId()));
            vo.setPostId(i.getPostId());
            SocialPost p = posts.get(i.getPostId());
            if (p != null) {
                String c = p.getContent();
                vo.setPostSnippet(c == null ? null : (c.length() > 60 ? c.substring(0, 60) + "…" : c));
            }
            vo.setCommentId(i.getCommentId());
            SocialComment c = comments.get(i.getCommentId());
            vo.setCommentContent(c == null || p == null ? null : c.getContent());
            vo.setRead(Integer.valueOf(1).equals(i.getIsRead()));
            vo.setCreateTime(i.getCreateTime());
            return vo;
        }).toList();
        return PageResult.of(out, total == null ? 0 : total, query.getPage(), query.getSize());
    }

    public long unreadCount(Long viewer) {
        Long n = interactionMapper.selectCount(Wrappers.<SocialInteraction>lambdaQuery()
                .eq(SocialInteraction::getRecipientId, viewer)
                .eq(SocialInteraction::getIsRead, 0));
        return n == null ? 0 : n;
    }

    public int markAllRead(Long viewer) {
        return interactionMapper.markAllRead(viewer);
    }

    /** 长按删除（只删自己收到的）。 */
    public void delete(Long viewer, Long id) {
        if (interactionMapper.delete(Wrappers.<SocialInteraction>lambdaQuery()
                .eq(SocialInteraction::getId, id)
                .eq(SocialInteraction::getRecipientId, viewer)) == 0) {
            throw new BusinessException("互动不存在");
        }
    }

    // ================= 汇总提示 =================

    /**
     * 跑一轮汇总：每个「有未读、且比上次汇总更新的互动」的人发一条站内提示。
     *
     * @return 这一轮发了几条提示
     */
    public int digestOnce() {
        return DistributedLockSupport.runLocked(redissonClient, DIGEST_LOCK, () -> {
            int sent = 0;
            for (Map<String, Object> row : interactionMapper.selectDigestCandidates(500)) {
                Long recipient = ((Number) row.get("recipientId")).longValue();
                long lastId = ((Number) row.get("lastId")).longValue();
                long maxId = ((Number) row.get("maxId")).longValue();
                long count = ((Number) row.get("cnt")).longValue();
                if (digestRecipient(recipient, lastId, maxId, count)) {
                    sent++;
                }
            }
            return sent;
        });
    }

    /**
     * 给一个人发一段互动的汇总提示：水位还停在 {@code lastId} 才推进到 {@code maxId} 并发提示（同一事务），推不动就什么也不做。
     *
     * @return true＝这次发了提示
     */
    public boolean digestRecipient(Long recipient, long lastId, long maxId, long count) {
        Boolean ok = transactionTemplate.execute(s -> {
            interactionMapper.ensureDigestRow(recipient);
            if (interactionMapper.advanceDigest(recipient, lastId, maxId) != 1) {
                return false;
            }
            notificationService.notify(recipient, VolunteerNotification.TYPE_SOCIAL_INTERACTIONS, "社区互动",
                    "你有 " + count + " 条新的点赞、评论或关注，去社区「互动」里看看", null, null);
            return true;
        });
        return Boolean.TRUE.equals(ok);
    }
}
