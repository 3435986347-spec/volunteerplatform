package com.hengde.donate.constant;

import java.time.LocalDateTime;

/**
 * 卷发放记录状态（与 V46 {@code mall_coupon_grant.status} 一致）。
 *
 * <p><b>「已过期」不是一个落库的状态</b>，是「未使用 且 {@code expire_time <= now}」现算出来的——
 * 与处置「到期靠比时间不靠 cron 改状态位」同一条：定时任务漏跑一次，过期的卷就还能用。
 * 所以 {@link #EXPIRED} 只出现在<b>展示与筛选</b>里，任何写路径都不会把它写进库。</p>
 *
 * @author hengde
 */
public final class MallCouponGrantStatus {

    private MallCouponGrantStatus() {
    }

    /** 未使用（是否仍可用还要看有效期）。 */
    public static final int UNUSED = 0;

    /** 已用在某张兑换单上；退单时凭 used_order_id CAS 归还为未使用。 */
    public static final int USED = 1;

    /** 已作废（后台作废，仅未使用的卷可作废）。 */
    public static final int REVOKED = 2;

    /** <b>派生态，不落库</b>：未使用且已到期。仅用于展示与「我的卷」筛选。 */
    public static final int EXPIRED = 3;

    /**
     * 展示用的状态：把「未使用但已到期」折成 {@link #EXPIRED}，其余原样返回。
     *
     * @param stored 库里的状态
     * @param validStart 有效期起
     * @param expireTime 到期时刻（不含）
     * @param now 当前时间，由调用方给出，保证一次请求里所有行用同一个「现在」
     */
    public static int displayStatus(Integer stored, LocalDateTime validStart, LocalDateTime expireTime,
                                    LocalDateTime now) {
        int s = stored == null ? UNUSED : stored;
        if (s == UNUSED && expireTime != null && !expireTime.isAfter(now)) {
            return EXPIRED;
        }
        return s;
    }

    public static String labelOf(int displayStatus) {
        return switch (displayStatus) {
            case UNUSED -> "未使用";
            case USED -> "已使用";
            case REVOKED -> "已作废";
            case EXPIRED -> "已过期";
            default -> "未知";
        };
    }
}
