package com.hengde.activity.vo;

import lombok.Data;

/**
 * 积分总览（积分中心顶部卡片），对应需求「积分中心：总积分、已使用积分」。
 *
 * <p>三项均由 {@code point_record} 聚合得出，恒等式 {@code balance = totalEarned - totalSpent}。</p>
 *
 * <p><b>「已使用」按来源判定，不按正负判定</b>（见
 * {@link com.hengde.activity.constant.PointSourceType#isConsumption}）：积分修正、管理员扣减、奖惩产生的
 * 负数流水都<b>不是</b>消费，它们冲减的是「累计获得」。否则把活动积分从 10 改成 5，
 * 页面会显示「已使用 5 分」，而志愿者根本没花过分。</p>
 *
 * <p>V2 尚无消费入口（兑换在 V3），故 {@code totalSpent} 当前恒为 0——这是正确的，
 * 不是漏统计。</p>
 *
 * @author hengde
 */
@Data
public class PointSummaryVO {

    /** 总积分（累计获得的净额：非消费类来源之和，含修正的正负增减） */
    private Integer totalEarned;

    /** 已使用积分（消费类来源支出之和，正数展示；V3 兑换上线前恒为 0） */
    private Integer totalSpent;

    /** 当前余额 */
    private Integer balance;
}
