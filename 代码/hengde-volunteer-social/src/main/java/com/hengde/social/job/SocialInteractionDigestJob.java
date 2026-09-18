package com.hengde.social.job;

import com.hengde.social.service.SocialInteractionFeedService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 互动汇总提示的触发层（Row 23 D「每隔20分钟……汇总提示一次」）。只判开关、调服务、记日志，业务在 {@link SocialInteractionFeedService#digestOnce}。
 * 调度总开关 {@code @EnableScheduling} 在 api。
 *
 * @author hengde
 */
@Slf4j
@Component
public class SocialInteractionDigestJob {

    private SocialInteractionFeedService feedService;

    @Value("${hengde.social.digest-enabled:true}")
    private boolean enabled;

    @Autowired
    public void setFeedService(SocialInteractionFeedService feedService) {
        this.feedService = feedService;
    }

    @Scheduled(cron = "${hengde.social.digest-cron:0 */20 * * * ?}")
    public void run() {
        if (!enabled) {
            return;
        }
        try {
            int sent = feedService.digestOnce();
            if (sent > 0) {
                log.info("[SOCIAL-DIGEST] 发出 {} 条互动汇总提示", sent);
            }
        } catch (Exception e) {
            log.error("[SOCIAL-DIGEST] 互动汇总失败（下一轮会补上）", e);
        }
    }
}
