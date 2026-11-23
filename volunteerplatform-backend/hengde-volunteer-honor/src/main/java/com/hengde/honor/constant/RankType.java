package com.hengde.honor.constant;

import java.util.List;

/**
 * 榜单板块。
 *
 * <p>需求原文是「4 个大板块：活动次数、活动时长、积分、微心愿」。<b>微心愿排行 V2 不做</b>——
 * 它的数据源属 {@code donate} 领域，该领域尚未建设。但取值 {@link #WISH} 在此<b>先占位</b>，
 * 快照表的 {@code rank_type} 也一并预留，V3 建完 donate 只需补数据源接入，
 * 不必改表结构、不必重排码值。</p>
 *
 * @author hengde
 */
public final class RankType {

    private RankType() {
    }

    /** 活动次数排行 */
    public static final int ATTENDANCE_COUNT = 1;
    /** 活动时长排行（分钟） */
    public static final int SERVICE_MINUTES = 2;
    /** 积分排行（累计获得） */
    public static final int POINTS = 3;
    /** 微心愿排行（V3 微心愿批放行；数据源 donate 的 {@code DonateRankingQueryService}，按「已实现」算） */
    public static final int WISH = 4;

    /**
     * 当前<b>已有数据源</b>的板块——查询与快照生成都只认这些。
     *
     * <p>{@link #WISH} 在 V2 时被刻意挡在外面：那时它有码但没数据源，混进来会静默产出空榜单，
     * 把「功能未开放」伪装成「没人上榜」。V3 微心愿批建好了数据源才放进来。
     * ⚠️ 放进来的连带后果：每日的快照任务会为<b>此前已冻结</b>的往期补冻这个板块（那时还没有实现的心愿，
     * 于是冻出空榜）——这是正确的：那些月份确实没有人圆过心愿。</p>
     */
    public static final List<Integer> AVAILABLE = List.of(ATTENDANCE_COUNT, SERVICE_MINUTES, POINTS, WISH);

    /**
     * 板块中文名，供出参直接展示。
     *
     * @param rankType 板块码
     * @return 中文名；未知码返回「未知榜单」
     */
    public static String labelOf(Integer rankType) {
        if (rankType == null) {
            return "未知榜单";
        }
        return switch (rankType) {
            case ATTENDANCE_COUNT -> "活动次数排行";
            case SERVICE_MINUTES -> "活动时长排行";
            case POINTS -> "积分排行";
            case WISH -> "微心愿排行";
            default -> "未知榜单";
        };
    }

    /**
     * 指标单位，供前端在数值后直接拼接。
     *
     * @param rankType 板块码
     * @return 单位；未知码返回空串
     */
    public static String unitOf(Integer rankType) {
        if (rankType == null) {
            return "";
        }
        return switch (rankType) {
            case ATTENDANCE_COUNT -> "次";
            case SERVICE_MINUTES -> "分钟";
            case POINTS -> "分";
            case WISH -> "个";
            default -> "";
        };
    }
}
