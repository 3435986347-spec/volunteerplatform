package com.hengde.trade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 收付配置，绑定 {@code hengde.trade.*}。
 *
 * <p><b>微信支付默认关闭</b>：它要协会的商户资质（《协会待确认清单-v3》⑩，周级前置）。
 * 关闭时下单一律被拒绝并说清原因，<b>不伪造一个「支付成功」</b>——
 * 与快递100 未开通时「如实说未开通」是同一条纪律。</p>
 *
 * <p><b>TTL 默认 15 分钟、各 {@code bizType} 可覆盖</b>：商城快递费占着库存，15 分钟合理；
 * <b>众筹捐款不占任何资源，15 分钟关单只会平白打断一笔捐赠</b>（V3规划·trade 批）。</p>
 *
 * @author hengde
 */
@Data
@Component
@ConfigurationProperties(prefix = "hengde.trade")
public class TradeProperties {

    /** 交易单默认有效期（分钟）。 */
    private int defaultTtlMinutes = 15;

    /**
     * 各业务类型的有效期覆盖，键是 {@code TradeFlow.BIZ_*} 的数值。
     *
     * <p><b>众筹（2）与结对捐款（3）默认一天</b>，不是 15 分钟：它们不占任何资源，
     * 15 分钟关单只会平白打断一笔捐赠（V3规划·trade 批）。</p>
     *
     * <p>⚠️ <b>默认值写在这里而不是只写在 api 的 yaml 里</b>：yaml 只在 api 的上下文生效，
     * 领域模块的测试上下文根本读不到它——那样「捐款给一天」就成了一句只在部署里成立的话，
     * 而用例会以为自己验过了（这条正是被用例当场撞出来的）。</p>
     */
    private Map<Integer, Integer> ttlMinutesByBizType = new LinkedHashMap<>(
            Map.of(2, 1440, 3, 1440));

    /** 分钟级扫描：捞「已发起未终态」的单去主动查单。<b>这是 D2 四道里的第二道，不是可选项。</b> */
    private Scan scan = new Scan();

    /** 每日对账：<b>D2 四道里的第四道</b>，结果落 {@code trade_reconcile_run}。 */
    private Reconcile reconcile = new Reconcile();

    private Wechat wechat = new Wechat();

    @Data
    public static class Scan {

        /** 总开关。⚠️ 关掉它等于把「回调丢了就再也没人管」变成常态——只建议排障时临时关。 */
        private boolean enabled = true;

        /** 扫描间隔 cron。 */
        private String cron = "0 */2 * * * ?";

        /** 每轮最多处理多少单（一次查太多会被微信限流，也会把单次执行拖很久）。 */
        private int batch = 100;

        /** 下单后超过这么多分钟仍未终态才去查单——刚下的单还没人付，查了也是「未支付」。 */
        private int minAgeMinutes = 2;
    }

    @Data
    public static class Reconcile {

        /** 总开关。渠道未开通时照样跑、落一行「跳过」——那一行告诉人「今天没有对过」。 */
        private boolean enabled = true;

        /**
         * 执行时刻 cron，核对的是<b>前一天</b>。默认凌晨 3:30：
         * 离零点留出足够余量，让前一天最后几分钟的回调与扫描先落定。
         */
        private String cron = "0 30 3 * * ?";
    }

    /** 微信支付 APIv3 凭据。 */
    @Data
    public static class Wechat {

        /** 是否开通。<b>默认关</b>：没有商户资质时保持关闭，下单会被明确拒绝。 */
        private boolean enabled = false;

        /** 商户号。 */
        private String mchId;

        /** 小程序 appId（与 {@code hengde.wechat.miniapp} 同一个应用）。 */
        private String appId;

        /** 商户 APIv3 密钥（回调解密用，生产走环境变量）。 */
        private String apiV3Key;

        /** 商户 API 证书序列号。 */
        private String merchantSerialNumber;

        /** 商户 API 私钥（PEM 文本或文件路径，生产走环境变量）。 */
        private String privateKey;

        /** 支付结果通知地址（完整地址，含 {@code /api} context-path；必须是已备案域名的 https）。 */
        private String payNotifyUrl;

        /** 退款结果通知地址。 */
        private String refundNotifyUrl;

        private int connectTimeoutMs = 5000;

        private int readTimeoutMs = 10000;
    }
}
