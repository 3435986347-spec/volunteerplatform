package com.hengde.honor.constant;

/**
 * 榜单周期。
 *
 * <p><b>「总榜」与另外两个不是一回事</b>：月榜、年榜有「往期」概念（需求里的历史月份下拉框），
 * 名次必须冻结成快照；而总榜永远指「截至此刻的全部累计」，不存在「历史的总榜」这种东西，
 * 故总榜<b>恒为实时聚合、从不快照</b>，{@code honor_ranking_snapshot} 表里也不会有它的行。</p>
 *
 * @author hengde
 */
public final class RankPeriodType {

    private RankPeriodType() {
    }

    /** 月榜，periodKey 形如 {@code 2026-07} */
    public static final int MONTH = 1;
    /** 年榜，periodKey 形如 {@code 2026} */
    public static final int YEAR = 2;
    /** 总榜，无 periodKey（恒为当期，不快照） */
    public static final int TOTAL = 3;

    /**
     * 总榜的 periodKey 占位值。
     *
     * <p>总榜没有周期标识，但出参里仍回一个值，免得前端拿到 null 还要特判。</p>
     */
    public static final String TOTAL_KEY = "all";

    /**
     * 周期中文名，供出参直接展示。
     *
     * @param periodType 周期码
     * @return 中文名；未知码返回「未知周期」
     */
    public static String labelOf(Integer periodType) {
        if (periodType == null) {
            return "未知周期";
        }
        return switch (periodType) {
            case MONTH -> "月榜";
            case YEAR -> "年榜";
            case TOTAL -> "总榜";
            default -> "未知周期";
        };
    }
}
