package com.hengde.social.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 社区配置（{@code hengde.social.*}）。默认值写在这里而不只写在 api 的 yaml——领域测试读不到 api 的 yaml。
 *
 * @author hengde
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "hengde.social")
public class SocialProperties {

    /** 「最热」的加权：查看 / 点赞 / 评论 / 分享各记几分（V4规划 D6：按小时分桶） */
    private int hotViewWeight = 1;
    private int hotLikeWeight = 3;
    private int hotCommentWeight = 5;
    private int hotShareWeight = 4;

    /** 「最热」每次从小时桶里取多少候选（再按可见性过滤、分页） */
    private int hotCandidates = 300;

    /** 帖子视频上限（字节，默认 100MB，Q11） */
    private long videoMaxBytes = 100L * 1024 * 1024;

    /** 帖子视频扩展名白名单 */
    private List<String> videoExtensions = List.of("mp4", "mov");

    /** 视频直传签名有效期（秒；还受 hengde.oss.presign-max-ttl-seconds 约束） */
    private int videoPresignTtlSeconds = 300;
}
