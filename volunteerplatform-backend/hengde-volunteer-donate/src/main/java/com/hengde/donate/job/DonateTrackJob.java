package com.hengde.donate.job;

import com.hengde.donate.service.DonateTrackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 物流轨迹轮询的<b>触发层</b>：只调服务、记日志，不含业务逻辑——形态与 {@code ActivityStartReminderJob} 一致。
 * 调度总开关 {@code @EnableScheduling} 在 api 模块，领域模块测试上下文不起调度线程。
 *
 * <p>开关判定在服务里（快递100 未开通 / {@code poll-enabled=false} 时直接返回 0），这里不重复。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class DonateTrackJob {

    private DonateTrackService trackService;

    @Autowired
    public void setTrackService(DonateTrackService trackService) {
        this.trackService = trackService;
    }

    /** 默认每 2 小时的第 20 分钟跑一次。 */
    @Scheduled(cron = "${hengde.donate.logistics.poll-cron:0 20 */2 * * ?}")
    public void poll() {
        try {
            int n = trackService.pollDue();
            if (n > 0) {
                log.info("物流轨迹轮询完成，本轮查询 {} 单", n);
            }
        } catch (Exception e) {
            log.error("物流轨迹轮询任务执行失败", e);
        }
    }
}
