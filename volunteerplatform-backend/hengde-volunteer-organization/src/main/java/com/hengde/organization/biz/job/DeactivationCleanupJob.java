package com.hengde.organization.biz.job;

import com.hengde.organization.biz.service.DeactivationCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 注销后让出组织位置的<b>兜底</b>：注销事件是进程内、不持久的，监听器一次失败（或进程在提交后、回调前重启）
 * 就要靠这里补上。只判调服务、记日志；{@code @EnableScheduling} 在 api 模块，领域模块的测试上下文不起调度线程。
 *
 * @author hengde
 */
@Slf4j
@Component
public class DeactivationCleanupJob {

    private DeactivationCleanupService cleanupService;

    @Autowired
    public void setCleanupService(DeactivationCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    /** 默认每小时第 17 分钟跑一次。 */
    @Scheduled(cron = "${hengde.organization.deactivation-cleanup-cron:0 17 * * * ?}")
    public void sweep() {
        try {
            int n = cleanupService.cleanupAll();
            if (n > 0) {
                log.info("注销收尾：本轮为 {} 个已注销账号让出组织位置", n);
            }
        } catch (Exception e) {
            log.error("注销收尾（让出组织位置）任务执行失败", e);
        }
    }
}
