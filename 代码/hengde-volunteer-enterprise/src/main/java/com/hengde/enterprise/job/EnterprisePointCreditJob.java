package com.hengde.enterprise.job;

import com.hengde.enterprise.config.EnterpriseProperties;
import com.hengde.enterprise.service.EnterprisePointService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 爱心企业兑换入账补记（触发层，只判开关、调服务、记日志）。调度总开关在 api 的 {@code AsyncConfig}。
 *
 * @author hengde
 */
@Slf4j
@Component
public class EnterprisePointCreditJob {

    private EnterprisePointService pointService;
    private EnterpriseProperties properties;

    @Autowired
    public void setPointService(EnterprisePointService pointService) {
        this.pointService = pointService;
    }

    @Autowired
    public void setProperties(EnterpriseProperties properties) {
        this.properties = properties;
    }

    @Scheduled(cron = "${hengde.enterprise.points.credit-cron:0 */10 * * * ?}")
    public void run() {
        if (!properties.getPoints().isCreditEnabled()) {
            return;
        }
        try {
            int n = pointService.creditPickedOrders(LocalDateTime.now().minusHours(Math.max(1, properties.getPoints().getCreditLookbackHours())));
            if (n > 0) {
                log.info("[EnterprisePoints] 补记兑换入账 {} 条", n);
            }
        } catch (Exception e) {
            log.error("[EnterprisePoints] 补记兑换入账失败", e);
        }
    }
}
