package com.hengde.social.service;

import com.hengde.auth.constant.SanctionScope;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import org.redisson.api.RedissonClient;
import com.hengde.social.dao.SocialLikeMapper;
import com.hengde.social.dao.SocialPostMapper;
import com.hengde.social.entity.SocialPost;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 点赞 / 取消点赞。
 *
 * <p><b>防重靠唯一键 {@code uk_post_volunteer}，计数靠影响行数</b>：插进去了才 +1、删掉了才 -1，
 * 连点、弱网重放都不会把点赞量记多（V4规划承重条款 5：不在 Java 里先查后插）。</p>
 *
 * <p>点赞挂处置闸门（禁止点赞 / 限制发布社区 / 拒绝使用）；<b>取消点赞不挂</b>——被禁言的人撤回自己点过的赞不该被拦。</p>
 *
 * <p>⚠️ <b>同一个人的点赞 / 取消按人上锁</b>（{@link #LOCK_PREFIX}，锁在事务外）——并发用例当场撞出来的：同一个人连点「赞 / 取消」时，
 * 取消删掉那一行还没提交，两次点赞的 INSERT 都在它上面等 S 锁；取消一提交，两个 S 锁同时拿到，接着都要插入意向锁、互相等对方的 S，
 * 死锁（与积分账本「多个 loser 持 S 再抢 X」同一形状）。唯一键防得住重复，防不住这个；不同人的点赞键不同，不受影响。</p>
 *
 * @author hengde
 */
@Service
public class SocialInteractionService {

    private SocialPostMapper postMapper;
    private SocialLikeMapper likeMapper;
    private SocialPostService postService;
    private SocialGateService gate;
    private HotRankService hotRankService;
    private SocialInteractionFeedService feedService;

    @Autowired
    public void setFeedService(SocialInteractionFeedService feedService) {
        this.feedService = feedService;
    }
    private TransactionTemplate transactionTemplate;
    private RedissonClient redissonClient;

    /** 社区里「一个人的关系类写入」（点赞、关注、不让TA看）共用这一把人维度的锁。 */
    public static final String LOCK_PREFIX = "lock:social:volunteer:";

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Autowired
    public void setPostMapper(SocialPostMapper postMapper) {
        this.postMapper = postMapper;
    }

    @Autowired
    public void setLikeMapper(SocialLikeMapper likeMapper) {
        this.likeMapper = likeMapper;
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
    public void setHotRankService(HotRankService hotRankService) {
        this.hotRankService = hotRankService;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** @return true＝这次真的点上了；false＝之前已经赞过（不报错） */
    public boolean like(Long viewer, Long postId) {
        gate.requireActor(viewer);
        Boolean added = DistributedLockSupport.runLocked(redissonClient, LOCK_PREFIX + viewer, () -> transactionTemplate.execute(s -> {
            gate.assertNotRestricted(viewer, SanctionScope.COMMUNITY_LIKE, "点赞");
            SocialPost p = postService.requireVisible(viewer, postId);
            if (!postService.likeable(p, postService.authorSetting(p))) {
                throw new BusinessException("这条帖子不允许点赞");
            }
            try {
                likeMapper.insert(postId, viewer);
            } catch (DuplicateKeyException e) {
                return false;
            }
            postMapper.incLike(postId);
            feedService.liked(viewer, p);
            return true;
        }));
        if (Boolean.TRUE.equals(added)) {
            hotRankService.bumpLike(postId);
        }
        return Boolean.TRUE.equals(added);
    }

    /** @return true＝这次真的取消了；false＝本来就没赞（不报错） */
    public boolean unlike(Long viewer, Long postId) {
        Boolean removed = DistributedLockSupport.runLocked(redissonClient, LOCK_PREFIX + viewer, () -> transactionTemplate.execute(s -> {
            if (likeMapper.delete(postId, viewer) == 0) {
                return false;
            }
            postMapper.decLike(postId);
            return true;
        }));
        return Boolean.TRUE.equals(removed);
    }
}
