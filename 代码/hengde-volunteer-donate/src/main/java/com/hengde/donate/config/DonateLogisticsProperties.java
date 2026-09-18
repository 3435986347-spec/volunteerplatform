package com.hengde.donate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 物流查询配置，绑定 {@code hengde.donate.logistics.*}。
 *
 * <p><b>快递100 默认关闭</b>：它要协会的账号（清单 ⑧：捐书批的直接前置，天级、可控）。
 * 关闭时一切照常——物流轨迹只是附加信息，我们自己的流转轨迹（到货 / 核对 / 装箱 / 送达）不依赖它。</p>
 *
 * @author hengde
 */
@Data
@Component
@ConfigurationProperties(prefix = "hengde.donate.logistics")
public class DonateLogisticsProperties {

    /** 志愿者端查看轨迹时，快照超过这么多分钟才去快递100 重查——每看一次就查一次是真金白银。 */
    private int refreshMinutes = 30;

    /** 定时轮询：两次查询同一运单的最小间隔（分钟）。 */
    private int pollIntervalMinutes = 120;

    /** 定时轮询：每轮最多查多少单（快递100 有并发与频率限制）。 */
    private int pollBatch = 50;

    /** 登记寄出超过这么多天仍未到终态的运单停止轮询——否则一个填错的单号会被查一辈子。 */
    private int trackMaxDays = 30;

    /** 定时轮询总开关（同时要求快递100 已开通）。 */
    private boolean pollEnabled = true;

    private Kuaidi100 kuaidi100 = new Kuaidi100();

    /** 订阅推送（物流推送批，V56）。与查询是两个计费项，开关分开。 */
    private Push push = new Push();

    /**
     * 快递100 订阅推送（{@code poll}）。<b>默认关闭</b>：要已备案域名的 https 回调地址（清单 ⑧，周级前置）。
     *
     * <p>授权 key 与查询共用 {@link Kuaidi100#getKey()}（同一个快递100 账号）。</p>
     */
    @Data
    public static class Push {

        private boolean enabled = false;

        /**
         * 回调地址，完整地址含 context-path，<b>不带查询参数</b>——运单 id 由系统拼在后面（{@code ?sid=}）。
         * 例：{@code https://已备案域名/api/callback/logistics/kuaidi100}
         */
        private String callbackUrl;

        private String endpoint = "https://poll.kuaidi100.com/poll";

        /** 每轮最多发起多少单订阅。 */
        private int subscribeBatch = 50;

        /** 订阅失败后隔这么多分钟再试。 */
        private int retryIntervalMinutes = 30;

        /** 失败这么多次就放弃、交还轮询（被快递100 明确拒绝的快递公司 / 单号不重试，直接放弃）。 */
        private int maxAttempts = 5;
    }

    /** 快递100 实时查询接口（{@code poll/query.do}）的凭据。 */
    @Data
    public static class Kuaidi100 {

        private boolean enabled = false;

        /** 快递100 分配的 customer。 */
        private String customer;

        /** 快递100 授权 key（签名用，生产走环境变量）。 */
        private String key;

        private String endpoint = "https://poll.kuaidi100.com/poll/query.do";

        private int connectTimeoutMs = 5000;

        private int readTimeoutMs = 10000;
    }
}
