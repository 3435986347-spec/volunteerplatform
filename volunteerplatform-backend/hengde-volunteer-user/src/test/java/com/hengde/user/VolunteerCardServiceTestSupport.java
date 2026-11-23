package com.hengde.user;

import java.util.Random;

/** 志愿者证用例造数：形状合法的随机令牌（32 个 base64url 字符）。 */
final class VolunteerCardServiceTestSupport {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private VolunteerCardServiceTestSupport() {
    }

    static String randomToken(Random rnd) {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
