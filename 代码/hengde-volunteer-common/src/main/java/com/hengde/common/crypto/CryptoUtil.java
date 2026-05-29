package com.hengde.common.crypto;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 字段级加解密与可查询哈希工具。
 *
 * <p>用于身份证号、手机号等敏感 PII 的「加密存储、按需解密」：</p>
 * <ul>
 *     <li><b>加密</b>：AES-256/GCM，每次随机 12 字节 IV，输出 {@code base64(IV‖密文+tag)}。
 *         GCM 自带完整性校验，无需另叠 HMAC 防篡改。绝不使用 ECB。</li>
 *     <li><b>哈希</b>：HMAC-SHA256（确定性），供唯一约束/等值查询用——AES 带随机 IV 无法直接等值查。
 *         哈希前先规范化（身份证转大写去空格、手机号只留数字），保证同一值哈希一致。</li>
 * </ul>
 *
 * <p>密钥由 {@link SecurityProperties} 的字符串经 SHA-256 派生为 256 位密钥。
 * 依赖按项目约定用 setter 注入。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class CryptoUtil {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();

    private SecretKeySpec aesKey;
    private SecretKeySpec hmacKey;

    @Autowired
    public void setSecurityProperties(SecurityProperties properties) {
        // 用 SHA-256 把任意长度的配置串派生成定长密钥，调用方不必凑 16/24/32 字节
        this.aesKey = new SecretKeySpec(sha256(properties.getAesKey()), "AES");
        this.hmacKey = new SecretKeySpec(sha256(properties.getHmacKey()), "HmacSHA256");
    }

    /**
     * 加密：返回 {@code base64(IV‖密文)}。入参为 null 返回 null。
     */
    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("[Crypto] 加密失败", e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "数据加密失败");
        }
    }

    /**
     * 解密 {@link #encrypt} 的输出。入参为 null 返回 null。
     */
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            byte[] iv = new byte[IV_LENGTH];
            byte[] cipherText = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[Crypto] 解密失败", e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "数据解密失败");
        }
    }

    /**
     * 身份证号的可查询哈希：先转大写、去空格，再 HMAC-SHA256。用于唯一约束/查重。
     */
    public String hashIdCard(String idCard) {
        if (idCard == null) {
            return null;
        }
        return hmac(idCard.trim().toUpperCase());
    }

    /**
     * 手机号的可查询哈希：先只保留数字，再 HMAC-SHA256。用于唯一约束/查重。
     */
    public String hashPhone(String phone) {
        if (phone == null) {
            return null;
        }
        return hmac(phone.replaceAll("\\D", ""));
    }

    private String hmac(String normalized) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("[Crypto] HMAC 计算失败", e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "数据处理失败");
        }
    }

    private byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
