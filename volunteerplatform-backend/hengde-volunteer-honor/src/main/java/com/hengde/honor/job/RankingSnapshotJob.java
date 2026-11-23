package com.hengde.honor.job;

import com.hengde.honor.config.HonorProperties;
import com.hengde.honor.service.RankingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 排行榜快照的<b>触发层</b>：把「什么时候跑」与「跑什么」分开。
 *
 * <p>本类刻意只做三件事——判开关、调服务、记日志，<b>不含任何业务逻辑</b>。
 * 冻结哪个周期、是否已冻结、写多少行，全部在 {@link RankingService#generateClosedPeriodSnapshots()} 里。</p>
 *
 * <p><b>为什么先用 {@code @Scheduled} 而不是项目技术栈里写的 XXL-Job</b>：XXL-Job 需要额外部署一个
 * 独立的调度中心（{@code xxl-job-admin}：自己的 Spring Boot 应用 + 自己的一套 {@code xxl_job} 库表），
 * 执行器启动后要注册上去才能被配置和触发。该组件在本项目<b>尚未部署</b>，
 * 现在硬接会得到一个既跑不起来、也无法端到端验证的半成品。用 {@code @Scheduled} 可以立刻跑通并验证，
 * 且因为触发层薄，将来换 XXL-Job 只是<b>在本类旁边加一个 {@code @XxlJob} 处理器</b>调同一个方法，
 * 业务代码一行不动。</p>
 *
 * <p>调度总开关 {@code @EnableScheduling} 在 api 模块——按项目约定，
 * 「主动改变应用行为」的配置归启动模块，领域模块只提供被扫描到的 bean。
 * 这也让领域模块的测试上下文默认不起调度线程。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class RankingSnapshotJob {

    private RankingService rankingService;
    private HonorProperties honorProperties;

    @Autowired
    public void setRankingService(RankingService rankingService) {
        this.rankingService = rankingService;
    }

    @Autowired
    public void setHonorProperties(HonorProperties honorProperties) {
        this.honorProperties = honorProperties;
    }

    /**
     * 补齐已过冷静期的上月 / 上年快照（默认每天 00:30）。
     *
     * <p>幂等：已冻结的周期会被跳过，故每天重跑无副作用，漏跑一天次日自动补上。</p>
     */
    @Scheduled(cron = "${hengde.honor.ranking.snapshot-cron:0 30 0 * * ?}")
    public void generateClosedPeriodSnapshots() {
        if (!honorProperties.getRanking().isSnapshotEnabled()) {
            return;
        }
        try {
            int generated = rankingService.generateClosedPeriodSnapshots();
            if (generated > 0) {
                log.info("排行榜快照定时任务完成，新冻结 {} 个周期", generated);
            }
        } catch (Exception e) {
            // 定时线程里漏出去的异常会中断后续调度，必须在此兜住
            log.error("排行榜快照定时任务异常", e);
        }
    }
}
