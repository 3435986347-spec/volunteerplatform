package com.hengde.common.utils;

import cn.hutool.crypto.digest.BCrypt;

/**
 * 密码加密工具类（基于 Hutool 的 BCrypt）。
 *
 * <p>为什么用 BCrypt 而不是 MD5/SHA：BCrypt 每次加密都会自动生成随机「盐」并混入结果，
 * 所以同一个密码两次加密出的密文也不同，能有效抵御彩虹表/撞库；它还故意算得慢，
 * 增加暴力破解成本。密文里已包含盐，校验时无需单独存盐。</p>
 *
 * @author hengde
 */
public class PasswordUtil {

    /** 工具类不允许实例化 */
    private PasswordUtil() {
    }

    /**
     * 加密明文密码。
     *
     * @param rawPassword 用户输入的明文密码
     * @return 加密后的密文（可直接存数据库）
     */
    public static String encrypt(String rawPassword) {
        // hashpw 内部自动调用 gensalt() 生成随机盐
        return BCrypt.hashpw(rawPassword);
    }

    /**
     * 校验明文密码与密文是否匹配（登录时用）。
     *
     * @param rawPassword       用户本次输入的明文密码
     * @param encryptedPassword 数据库里存的密文
     * @return 匹配返回 true，否则 false
     */
    public static boolean matches(String rawPassword, String encryptedPassword) {
        return BCrypt.checkpw(rawPassword, encryptedPassword);
    }
}
