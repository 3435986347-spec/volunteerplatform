package com.hengde.enterprise.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 爱心企业（{@code hengde.enterprise}，V4 爱心企业批）。
 *
 * @author hengde
 */
@Data
@Component
@ConfigurationProperties(prefix = "hengde.enterprise")
public class EnterpriseProperties {

    private Points points = new Points();

    @Data
    public static class Points {
        /** 兑换入账补记任务开关 */
        private boolean creditEnabled = true;
        /** 补记任务 cron（默认每 10 分钟） */
        private String creditCron = "0 */10 * * * ?";
        /**
         * 回看窗口（小时）：只扫领取时间在这之内的单。<b>不设下界会让任务第一次运行就把全部历史兑换单扫一遍</b>；
         * 停机超过窗口漏掉的，用后台 {@code POST /a/enterprise/points/reconcile?since=} 显式补。
         */
        private int creditLookbackHours = 72;
    }
}
