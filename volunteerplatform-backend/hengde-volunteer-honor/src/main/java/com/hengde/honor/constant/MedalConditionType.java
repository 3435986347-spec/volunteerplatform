package com.hengde.honor.constant;

/**
 * 勋章获取条件类型。
 *
 * <p><b>本批只存不判</b>：自动发放引擎未实现，这里是协会规则的登记位，
 * 所有发放当前都由后台手动发起（并仍须过发放审核）。</p>
 *
 * <p>但<b>「获取进度」现在就能展示</b>——三种阈值的当前值在既有服务里都读得到
 * （时长/次数取 {@code ActivityRankingQueryService}——只认已发布/已结束活动上的真实签到，
 * 不是服务记录那份不筛活动状态的粗口径；
 * 积分取 {@code PointService.summary} 的「累计获得」）。展示进度与自动发放是两件事，
 * 前者不需要引擎，没必要为了等引擎而让志愿者端连「还差多少」都看不到。</p>
 *
 * <p><b>积分口径用「累计获得」而非余额</b>：与排行榜、积分中心保持同一口径——
 * 花掉积分不该让人丢掉已经挣到的勋章进度。</p>
 *
 * @author hengde
 */
public final class MedalConditionType {

    private MedalConditionType() {
    }

    /** 手动授予：无阈值，不展示进度 */
    public static final int MANUAL = 0;
    /** 累计服务时长（分钟，秘书部已确认的） */
    public static final int SERVICE_MINUTES = 1;
    /** 累计参与活动次数 */
    public static final int ACTIVITY_COUNT = 2;
    /** 累计获得积分（非消费来源之和，与积分中心 totalEarned 同口径） */
    public static final int EARNED_POINTS = 3;

    /** 最大合法取值，供入参校验 */
    public static final int MAX = EARNED_POINTS;

    /**
     * 条件类型中文名。
     *
     * @param conditionType 条件码
     * @return 中文名；未知码返回「未知条件」
     */
    public static String labelOf(Integer conditionType) {
        if (conditionType == null) {
            return "未知条件";
        }
        return switch (conditionType) {
            case MANUAL -> "手动授予";
            case SERVICE_MINUTES -> "累计服务时长";
            case ACTIVITY_COUNT -> "累计活动次数";
            case EARNED_POINTS -> "累计获得积分";
            default -> "未知条件";
        };
    }

    /**
     * 该条件是否有可展示的进度。
     *
     * @param conditionType 条件码
     * @return true=可算进度
     */
    public static boolean hasProgress(Integer conditionType) {
        return conditionType != null && conditionType >= SERVICE_MINUTES && conditionType <= EARNED_POINTS;
    }
}
