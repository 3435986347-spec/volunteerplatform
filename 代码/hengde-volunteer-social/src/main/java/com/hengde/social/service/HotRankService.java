package com.hengde.social.service;

import com.hengde.social.config.SocialProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.protocol.ScoredEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「最热」（Row 23「显示阅读量、点赞量、评论量当前一个小时综合从高到低的排序」，V4规划 D6）。
 *
 * <p><b>按小时分桶放 Redis</b>（{@code social:hot:yyyyMMddHH}，3 小时过期）。「当前一个小时」按滑动窗口近似：
 * 本小时桶 + 上一小时桶 ×（本小时还没过去的比例）——整点刚过时不至于榜单一下清空。</p>
 *
 * <p><b>热度只是排序依据、不是账</b>：Redis 被清、宕机，最坏是「最热」暂时退化成按时间排；所以这里一切异常只打 WARN、不外抛，
 * 更不能让一次点赞因为 Redis 抖了而失败。累计的查看 / 点赞 / 评论 / 分享数落在帖子行上。取消点赞不扣热度（热度记的是「这一小时有多热闹」）。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class HotRankService {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH");
    static final String KEY_PREFIX = "social:hot:";

    private RedissonClient redissonClient;
    private SocialProperties properties;

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Autowired
    public void setProperties(SocialProperties properties) {
        this.properties = properties;
    }

    public void bumpView(Long postId) {
        bump(postId, properties.getHotViewWeight(), LocalDateTime.now());
    }

    public void bumpLike(Long postId) {
        bump(postId, properties.getHotLikeWeight(), LocalDateTime.now());
    }

    public void bumpComment(Long postId) {
        bump(postId, properties.getHotCommentWeight(), LocalDateTime.now());
    }

    public void bumpShare(Long postId) {
        bump(postId, properties.getHotShareWeight(), LocalDateTime.now());
    }

    void bump(Long postId, int weight, LocalDateTime now) {
        if (postId == null || weight <= 0) {
            return;
        }
        try {
            RScoredSortedSet<Long> bucket = bucket(now);
            bucket.addScore(postId, weight);
            bucket.expire(Duration.ofHours(3));
        } catch (Exception e) {
            log.warn("[SOCIAL-HOT] 记热度失败 postId={}（只影响「最热」排序）", postId, e);
        }
    }

    /** 按热度从高到低的帖子 id；Redis 不可用时返回空（调用方退回按时间排）。同分按 id 倒序（新的在前），保证分页稳定。 */
    public List<Long> topIds() {
        return topIds(LocalDateTime.now(), properties.getHotCandidates());
    }

    List<Long> topIds(LocalDateTime now, int limit) {
        try {
            double carry = 1.0 - now.getMinute() / 60.0;
            Map<Long, Double> scores = new HashMap<>();
            for (ScoredEntry<Long> e : bucket(now).entryRangeReversed(0, limit - 1)) {
                scores.merge(e.getValue(), e.getScore(), Double::sum);
            }
            for (ScoredEntry<Long> e : bucket(now.minusHours(1)).entryRangeReversed(0, limit - 1)) {
                scores.merge(e.getValue(), e.getScore() * carry, Double::sum);
            }
            List<Map.Entry<Long, Double>> ranked = new ArrayList<>(scores.entrySet());
            ranked.removeIf(e -> e.getValue() <= 0);
            ranked.sort(Map.Entry.<Long, Double>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())));
            return ranked.stream().limit(limit).map(Map.Entry::getKey).toList();
        } catch (Exception e) {
            log.warn("[SOCIAL-HOT] 读热度失败，「最热」退回按时间排", e);
            return List.of();
        }
    }

    private RScoredSortedSet<Long> bucket(LocalDateTime at) {
        return redissonClient.getScoredSortedSet(KEY_PREFIX + HOUR.format(at), LongCodec.INSTANCE);
    }
}
