package com.hengde.common.utils;

import org.springframework.util.StringUtils;

/**
 * 敏感信息脱敏工具。
 *
 * <p>身份证、手机号等以密文存储（见 {@link com.hengde.common.crypto.CryptoUtil}），
 * 列表/详情默认展示脱敏值；只有确需明文的场景才解密。各域统一调用本类，避免各写一套。</p>
 *
 * @author hengde
 */
public final class MaskUtil {

    private MaskUtil() {
    }

    /**
     * 手机号脱敏：保留前 3 后 4，如 {@code 138****1234}。
     */
    public static String maskPhone(String phone) {
        if (!StringUtils.hasText(phone) || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 身份证脱敏：保留前 4 后 4，中间打码，如 {@code 4101**********1234}。
     */
    public static String maskIdCard(String idCard) {
        if (!StringUtils.hasText(idCard) || idCard.length() < 8) {
            return idCard;
        }
        int maskLen = idCard.length() - 8;
        return idCard.substring(0, 4) + "*".repeat(maskLen) + idCard.substring(idCard.length() - 4);
    }

    /**
     * 姓名脱敏：保留姓，其余打码，如 {@code 张**}。
     */
    public static String maskName(String name) {
        if (!StringUtils.hasText(name)) {
            return name;
        }
        if (name.length() == 1) {
            return name;
        }
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }
}
