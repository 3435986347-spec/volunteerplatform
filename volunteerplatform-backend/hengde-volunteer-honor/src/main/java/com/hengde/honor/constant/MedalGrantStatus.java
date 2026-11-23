package com.hengde.honor.constant;

/**
 * 勋章发放记录的状态机。
 *
 * <pre>
 *   0待审核 ──通过──▶ 1已生效（志愿者可见；附带积分在此刻入账）
 *       └────驳回──▶ 2已驳回（不生效，留痕）
 * </pre>
 *
 * <p><b>志愿者端只看得到「已生效」</b>——与活动发布审核域隔离同理，
 * 待审/驳回的记录只在后台可见，否则等于让审核形同虚设。</p>
 *
 * @author hengde
 */
public final class MedalGrantStatus {

    private MedalGrantStatus() {
    }

    /** 待发放审核 */
    public static final int PENDING = 0;
    /** 已生效：志愿者可见 */
    public static final int EFFECTIVE = 1;
    /** 已驳回：不生效，但留痕；驳回后允许对同一人重新发起 */
    public static final int REJECTED = 2;

    /**
     * 状态中文名。
     *
     * @param status 状态码
     * @return 中文名；未知码返回「未知」
     */
    public static String labelOf(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case PENDING -> "待审核";
            case EFFECTIVE -> "已生效";
            case REJECTED -> "已驳回";
            default -> "未知";
        };
    }
}
