package com.hengde.social.constant;

/**
 * 私信的取值（V4 私信批，单一来源）。
 *
 * @author hengde
 */
public final class SocialChatCodes {

    private SocialChatCodes() {
    }

    /** 一条私信最多多少字 */
    public static final int MAX_CONTENT = 1000;

    /** 会话列表上那一行摘要留多少字 */
    public static final int SUMMARY_CHARS = 60;

    /**
     * 陌生人限额：对方一条都没回之前最多发几条（V4规划 Q9）。
     *
     * <p>判据是「对方在这条会话里发过几条」而不是关注关系——互关之后又互相取关，
     * 不该把已经聊着的对话突然锁上。</p>
     */
    public static final int STRANGER_LIMIT = 3;

    /** WebSocket 路径（握手带 token，见 api 的 WebSocketConfig） */
    public static final String WS_PATH = "/ws/social/chat";

    public static String reportSourceLabel(Integer source) {
        return Integer.valueOf(2).equals(source) ? "关键词命中" : "用户投诉";
    }

    public static String reportStatusLabel(Integer status) {
        if (status == null) {
            return "待处理";
        }
        return switch (status) {
            case 1 -> "成立";
            case 2 -> "不成立";
            default -> "待处理";
        };
    }
}
