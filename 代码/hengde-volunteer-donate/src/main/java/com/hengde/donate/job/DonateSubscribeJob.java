package com.hengde.donate.job;

import com.hengde.donate.service.DonateLogisticsPushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 快递100 订阅的<b>触发层</b>（V3 物流推送批）：只调服务、记日志，不含业务逻辑。
 *
 * <p>订阅不在「登记寄出」那个请求里同步发起：那要把一次按次计费、可能超时的外部调用挂进捐赠人的提交里，
 * 失败了还得决定要不要让登记也失败。物流轨迹是附加信息，晚几分钟订上无妨；开关判定在服务里。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class DonateSubscribeJob {

    private DonateLogisticsPushService pushService;

    @Autowired
    public void setPushService(DonateLogisticsPushService pushService) {
        this.pushService = pushService;
    }

    /** 默认每 5 分钟。 */
    @Scheduled(cron = "${hengde.donate.logistics.push.subscribe-cron:0 */5 * * * ?}")
    public void subscribe() {
        try {
            int n = pushService.subscribeDue();
            if (n > 0) {
                log.info("物流订阅：本轮向快递100 发起 {} 单", n);
            }
        } catch (Exception e) {
            log.error("物流订阅任务执行失败", e);
        }
    }
}
