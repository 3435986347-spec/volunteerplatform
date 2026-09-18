package com.hengde.api.config;

import com.hengde.auth.config.AuthProperties;
import com.hengde.common.crypto.SecurityProperties;
import com.hengde.donate.config.DonateLogisticsProperties;
import com.hengde.trade.config.TradeProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 生产配置守卫：非「开发/测试」profile 启动时做 fail-fast 校验，
 * 拒绝仍在使用 dev 弱默认密钥/口令的配置，避免漏配环境变量就把弱密钥带上生产而静默裸奔。
 *
 * <p>放行条件：active profile 含 dev/test/local 之一。否则（含「未指定任何 profile」）按生产口径强校验：
 * AES/HMAC 密钥不得为空或仍是 dev 默认值；启用初始超管时其密码不得仍是 admin123。
 * 任一不满足即抛异常中断启动——宁可起不来，也不让弱配置上线。</p>
 *
 * <p>仅在 api（唯一可部署单元）的 Spring 上下文生效；领域模块测试 classpath 不含 api，不受影响。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class ProductionConfigGuard {

    private static final Set<String> DEV_PROFILES = Set.of("dev", "test", "local");
    private static final String DEV_AES_KEY = "dev-only-aes-secret-change-in-prod";
    private static final String DEV_HMAC_KEY = "dev-only-hmac-secret-change-in-prod";
    private static final String DEV_ADMIN_PASSWORD = "admin123";

    private Environment environment;
    private SecurityProperties securityProperties;
    private AuthProperties authProperties;
    private TradeProperties tradeProperties;
    private DonateLogisticsProperties logisticsProperties;

    @Autowired
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Autowired
    public void setSecurityProperties(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Autowired
    public void setAuthProperties(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Autowired
    public void setTradeProperties(TradeProperties tradeProperties) {
        this.tradeProperties = tradeProperties;
    }

    @Autowired
    public void setLogisticsProperties(DonateLogisticsProperties logisticsProperties) {
        this.logisticsProperties = logisticsProperties;
    }

    @PostConstruct
    public void verify() {
        String[] active = environment.getActiveProfiles();
        boolean devLike = Arrays.stream(active).anyMatch(DEV_PROFILES::contains);
        if (devLike) {
            return;
        }
        if (active.length == 0) {
            throw new IllegalStateException(
                    "未指定运行 profile：生产请显式 --spring.profiles.active=prod，本地开发用 dev；"
                            + "拒绝以无 profile 方式启动，以免静默使用开发默认配置。");
        }
        List<String> problems = new ArrayList<>();
        if (isBlankOrDev(securityProperties.getAesKey(), DEV_AES_KEY)) {
            problems.add("SECURITY_AES_KEY 仍为开发默认值或为空");
        }
        if (isBlankOrDev(securityProperties.getHmacKey(), DEV_HMAC_KEY)) {
            problems.add("SECURITY_HMAC_KEY 仍为开发默认值或为空");
        }
        if (authProperties.isInitSuperAdmin() && DEV_ADMIN_PASSWORD.equals(authProperties.getSuperAdminPassword())) {
            problems.add("AUTH_SUPER_ADMIN_PASSWORD 仍为弱默认值 admin123");
        }
        if (authProperties.isDevLoginEnabled()) {
            problems.add("hengde.auth.dev-login-enabled 必须为 false（开发登录会绕过微信鉴权，禁止上生产）");
        }
        if (authProperties.isRealnameEnabled()
                && (isBlank(authProperties.getRealnameSecretId())
                || isBlank(authProperties.getRealnameSecretKey()))) {
            // 缺密钥时实名校验会在首次注册才抛错，等于把配置问题拖到线上真实用户身上，故启动即拦
            problems.add("hengde.auth.realname-enabled=true 但腾讯云密钥未配置"
                    + "（REALNAME_SECRET_ID / REALNAME_SECRET_KEY）");
        }
        TradeProperties.Wechat pay = tradeProperties.getWechat();
        if (pay.isEnabled()) {
            // 缺配置时，问题要到「用户点了支付」那一刻才暴露，而那时钱可能已经在路上了
            if (isBlank(pay.getMchId()) || isBlank(pay.getAppId()) || isBlank(pay.getApiV3Key())
                    || isBlank(pay.getMerchantSerialNumber()) || isBlank(pay.getPrivateKey())) {
                problems.add("hengde.trade.wechat.enabled=true 但商户配置不全"
                        + "（TRADE_WECHAT_MCH_ID / APP_ID / API_V3_KEY / SERIAL_NO / PRIVATE_KEY）");
            }
            // 回调地址缺失或不是 https：钱照样收得到，而我们【永远收不到通知】——
            // 那时只剩分钟级扫描在兜底，等于把四道里的第一道白白丢掉
            if (isBlank(pay.getPayNotifyUrl()) || !pay.getPayNotifyUrl().startsWith("https://")) {
                problems.add("hengde.trade.wechat.pay-notify-url 必须是已备案域名的 https 地址");
            }
            if (isBlank(pay.getRefundNotifyUrl()) || !pay.getRefundNotifyUrl().startsWith("https://")) {
                problems.add("hengde.trade.wechat.refund-notify-url 必须是已备案域名的 https 地址");
            }
        }
        DonateLogisticsProperties.Push push = logisticsProperties.getPush();
        if (push.isEnabled()) {
            // 开关开着、key 或回调地址缺失时，订阅客户端会静默判为「未开通」——看起来开了，其实一单都没订，
            // 而轮询因为「推送已开」又跳过了订阅中的运单。启动即拦，别让它变成一个没有征兆的空转
            if (isBlank(logisticsProperties.getKuaidi100().getKey())) {
                problems.add("hengde.donate.logistics.push.enabled=true 但快递100 授权 key 未配置（KUAIDI100_KEY）");
            }
            if (isBlank(push.getCallbackUrl()) || !push.getCallbackUrl().startsWith("https://")) {
                problems.add("hengde.donate.logistics.push.callback-url 必须是已备案域名的 https 地址"
                        + "（KUAIDI100_PUSH_CALLBACK_URL）");
            }
        }
        if (authProperties.getAgreementVersion() == null || authProperties.getAgreementVersion().isBlank()) {
            problems.add("hengde.auth.agreement-version 不能为空");
        }
        if (isBlankOrDev(authProperties.getAgreementText(), AuthProperties.DEFAULT_AGREEMENT_TEXT)) {
            problems.add("hengde.auth.agreement-text 仍为占位/空文本（须配置正式协议正文，否则志愿者签的是占位协议）");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("生产配置校验未通过（profile=" + Arrays.toString(active) + "）：\n - "
                    + String.join("\n - ", problems)
                    + "\n请通过环境变量提供真实密钥/口令后再启动。");
        }
        log.info("生产配置守卫校验通过，profile={}", Arrays.toString(active));
    }

    private boolean isBlankOrDev(String value, String devValue) {
        return value == null || value.isBlank() || devValue.equals(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
