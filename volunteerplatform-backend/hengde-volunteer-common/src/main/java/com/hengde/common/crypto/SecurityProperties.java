package com.hengde.common.crypto;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 安全加密配置，绑定 application.yaml 里的 {@code hengde.security.*}。
 *
 * <p>白标多实例下每个组织一套独立密钥，故来自配置。两个密钥字符串可任意长度，
 * {@link CryptoUtil} 会用 SHA-256 派生定长密钥，调用方不必关心长度。</p>
 *
 * <p><b>生产必须用环境变量覆盖默认值。</b>默认值仅供本地开发/测试启动，绝不可用于线上。</p>
 *
 * @author hengde
 */
@Data
@Component
@ConfigurationProperties(prefix = "hengde.security")
public class SecurityProperties {

    /**
     * AES 加密密钥（任意字符串，经 SHA-256 派生为 256 位 AES 密钥）。
     * 默认值仅为让无配置的领域模块测试上下文能启动；api 的 application.yaml 会覆盖，生产用环境变量覆盖。
     */
    private String aesKey = "dev-only-aes-secret-change-in-prod";

    /** HMAC 密钥（用于身份证/手机号等的可查询哈希）。默认值同上，仅兜底。 */
    private String hmacKey = "dev-only-hmac-secret-change-in-prod";
}
