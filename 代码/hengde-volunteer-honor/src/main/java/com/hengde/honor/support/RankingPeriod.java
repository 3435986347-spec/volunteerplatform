package com.hengde.honor.support;

import com.hengde.common.exception.BusinessException;
import com.hengde.honor.constant.RankPeriodType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * 周期标识 ↔ 时间区间的<b>唯一换算处</b>。
 *
 * <p>「{@code 2026-07} 是哪一段时间」这个换算，查询端（实时聚合的 where 区间）与快照端
 * （生成哪一段的名次）必须完全一致，否则同一个 periodKey 在两条路径上算出的人不一样，
 * 快照与实时结果对不上。故收成一个值对象，两边都从这里取。</p>
 *
 * <p><b>区间是左闭右开 {@code [from, to)}</b>：按月切分时若用闭区间，
 * 月末 23:59:59 与次月 00:00:00 之间的记录归属会含糊，跨月边界的记录可能被两个月重复统计。</p>
 *
 * @param periodType 周期码，见 {@link RankPeriodType}
 * @param periodKey  规范化后的周期标识（总榜为 {@link RankPeriodType#TOTAL_KEY}）
 * @param from       起（含）；总榜为 null 表示不限
 * @param to         止（不含）；总榜为 null 表示不限
 * @author hengde
 */
public record RankingPeriod(int periodType, String periodKey, LocalDateTime from, LocalDateTime to) {

    private static final Pattern MONTH_KEY = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");
    private static final Pattern YEAR_KEY = Pattern.compile("^\\d{4}$");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * 解析周期。
     *
     * @param periodType 周期码
     * @param periodKey  周期标识；总榜忽略此参数
     * @return 值对象
     * @throws BusinessException 周期码未知或标识格式非法
     */
    public static RankingPeriod of(Integer periodType, String periodKey) {
        if (periodType == null) {
            throw new BusinessException("周期类型不能为空");
        }
        return switch (periodType) {
            case RankPeriodType.TOTAL ->
                    new RankingPeriod(RankPeriodType.TOTAL, RankPeriodType.TOTAL_KEY, null, null);
            case RankPeriodType.MONTH -> ofMonth(periodKey);
            case RankPeriodType.YEAR -> ofYear(periodKey);
            default -> throw new BusinessException("未知的榜单周期");
        };
    }

    private static RankingPeriod ofMonth(String periodKey) {
        if (periodKey == null || !MONTH_KEY.matcher(periodKey.trim()).matches()) {
            throw new BusinessException("月榜周期格式应为 yyyy-MM，如 2026-07");
        }
        YearMonth ym = YearMonth.parse(periodKey.trim(), MONTH_FORMAT);
        return new RankingPeriod(RankPeriodType.MONTH, ym.format(MONTH_FORMAT),
                ym.atDay(1).atStartOfDay(), ym.plusMonths(1).atDay(1).atStartOfDay());
    }

    private static RankingPeriod ofYear(String periodKey) {
        if (periodKey == null || !YEAR_KEY.matcher(periodKey.trim()).matches()) {
            throw new BusinessException("年榜周期格式应为 yyyy，如 2026");
        }
        int year = Integer.parseInt(periodKey.trim());
        return new RankingPeriod(RankPeriodType.YEAR, String.valueOf(year),
                LocalDate.of(year, 1, 1).atStartOfDay(), LocalDate.of(year + 1, 1, 1).atStartOfDay());
    }

    /** 当前月份的周期标识，如 {@code 2026-07}。 */
    public static String currentMonthKey(LocalDate today) {
        return YearMonth.from(today).format(MONTH_FORMAT);
    }

    /** 上一个月的周期标识。 */
    public static String previousMonthKey(LocalDate today) {
        return YearMonth.from(today).minusMonths(1).format(MONTH_FORMAT);
    }

    /** 上一年的周期标识。 */
    public static String previousYearKey(LocalDate today) {
        return String.valueOf(today.getYear() - 1);
    }

    /**
     * 该周期是否<b>已经结束</b>——只有结束了的周期才谈得上「冻结名次」。
     *
     * <p>用「已结束」而不是「不是当期」作为判据，是为了把<b>未来周期</b>一并归到实时聚合那侧：
     * 前端下拉框若因时区或本地时间偏差传来下个月的 key，应该得到一个空榜单，
     * 而不是去生成一份「未来的快照」。</p>
     *
     * @param now 当前时刻（显式传入，便于测试构造时间）
     * @return true=已结束
     */
    public boolean closed(LocalDateTime now) {
        return to != null && !to.isAfter(now);
    }
}
