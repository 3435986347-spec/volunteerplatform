package com.hengde.activity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * activity 域配置，绑定 {@code hengde.activity.*}。
 *
 * @author hengde
 */
@Data
@Component
@ConfigurationProperties(prefix = "hengde.activity")
public class ActivityProperties {

    /**
     * 「紧急上报 / 联系负责人」预设电话（前端 {@code tel:} 拨号，见原型 p92/p112）。
     * 按部署方配置 {@code hengde.activity.emergency-phone}；未配置则为 null，前端据此隐藏入口。
     */
    private String emergencyPhone;

    /** 「活动即将开始」提醒（V39 + {@code ActivityStartReminderJob}） */
    private Reminder reminder = new Reminder();

    /**
     * 活动开始提醒的配置。
     *
     * <p>三个值都刻意可配：提前多久、一次最多发多少、要不要发，都是运营口径而非技术常量。</p>
     */
    @Data
    public static class Reminder {

        /** 总开关。关掉之后定时任务直接返回，不留半发状态。 */
        private boolean enabled = true;

        /**
         * 提前多少小时提醒，默认 24。
         *
         * <p>取 24 是因为它同时覆盖「明天的活动」和「今天晚些时候的活动」，
         * 而不至于早到让人转头就忘。真正的取值由协会按运营习惯定。</p>
         */
        private int leadHours = 24;

        /**
         * 单次扫描最多处理多少个场次，默认 200。
         *
         * <p>封顶是为了让一次执行的耗时可预期：每个场次要发一批短信，
         * 没有上限时一个「周期发布了 60 场」的活动会让单次任务跑很久。
         * 没处理完的下一轮继续——扫描条件本身就是幂等的。</p>
         */
        private int batchLimit = 200;
    }
}
