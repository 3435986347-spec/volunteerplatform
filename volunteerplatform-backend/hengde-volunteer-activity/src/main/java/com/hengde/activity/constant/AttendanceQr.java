package com.hengde.activity.constant;

/**
 * 活动考勤二维码内容约定（单一来源），含签到 + 签退两类。
 *
 * <p>负责人端据此生成二维码图片展示、志愿者端扫码后据此校验「确属本活动」。两端须一致——
 * 小程序 {@code my-activities.js} 的 {@code CHECKIN_QR_PREFIX}/{@code CHECKOUT_QR_PREFIX} 与本类同值。
 * 二维码本身不是鉴权凭据，真正门槛是后端 GPS 距离 + 时间窗 + 报名校验。</p>
 *
 * @author hengde
 */
public final class AttendanceQr {

    /** 签到二维码内容前缀。 */
    public static final String CHECKIN_PREFIX = "hengde-activity-checkin:";

    /** 签退二维码内容前缀。 */
    public static final String CHECKOUT_PREFIX = "hengde-activity-checkout:";

    private AttendanceQr() {
    }

    /** 某活动的签到二维码内容：{@code hengde-activity-checkin:{activityId}}。 */
    public static String checkInContent(Long activityId) {
        return CHECKIN_PREFIX + activityId;
    }

    /** 某活动的签退二维码内容：{@code hengde-activity-checkout:{activityId}}。 */
    public static String checkOutContent(Long activityId) {
        return CHECKOUT_PREFIX + activityId;
    }
}
