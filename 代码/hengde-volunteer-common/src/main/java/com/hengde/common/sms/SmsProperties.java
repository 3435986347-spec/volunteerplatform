package com.hengde.common.sms;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 火山引擎短信通道配置，绑定 application.yaml 里的 {@code hengde.sms.*}。
 *
 * <p>白标多实例部署下，每个社会组织一套独立的 AK/SK、短信账号、签名与模板，
 * 因此这些值都来自配置而非硬编码。本类是被动的配置载体，{@link SmsService} 据此发送短信。</p>
 *
 * <p>{@link #enabled} 为 {@code false} 时（dev/test 默认），通道只打日志不真实发送，
 * 这样本地开发和 Testcontainers 测试无需真实凭证即可跑通，也不会产生短信费用。</p>
 *
 * @author hengde
 */
@Data
@Component
@ConfigurationProperties(prefix = "hengde.sms")
public class SmsProperties {

    /** 是否真实发送短信。false 时仅打日志（dev/test 默认） */
    private boolean enabled = false;

    /** 火山引擎 Access Key */
    private String accessKey;

    /** 火山引擎 Secret Key */
    private String secretKey;

    /** 短信签名（如「雷州市恒德爱心公益协会」） */
    private String signName;

    /** 火山短信账号（SmsAccount，控制台分配） */
    private String smsAccount;

    /** 服务区域，默认 cn-north-1 */
    private String region = "cn-north-1";

    /** 各用途短信模板 ID，键为业务用途（如 verify-code），值为火山模板 ID */
    private Map<String, String> templates = new HashMap<>();

    /** 验证码位数 */
    private int codeLength = 6;

    /** 验证码有效期（秒） */
    private int codeExpireSeconds = 300;

    /** 同一手机号同一场景的重发间隔（秒），间隔内重复请求会被拦截 */
    private int codeResendSeconds = 60;
}
