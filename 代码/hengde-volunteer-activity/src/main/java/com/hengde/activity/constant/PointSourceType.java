package com.hengde.activity.constant;

/**
 * 积分流水来源类型。
 *
 * <p>取值同时决定 {@code point_record.source_id} 指向哪张单据——这关系到唯一约束
 * {@code uk_source(source_type, source_id)} 的正确性，改动前务必确认不会撞键：</p>
 *
 * <table>
 *   <tr><th>来源</th><th>source_id 指向</th><th>说明</th></tr>
 *   <tr><td>{@link #ACTIVITY}</td><td>{@code activity_attendance.id}</td><td>一条考勤只发一次积分</td></tr>
 *   <tr><td>{@link #CORRECTION}</td><td>{@code activity_attendance_change.id}</td>
 *       <td><b>不可用 attendance.id</b>：同一考勤可被多次修正，会撞唯一键</td></tr>
 *   <tr><td>{@link #MEDAL}</td><td>{@code honor_medal_grant.id}</td><td>V2 第 3 批启用</td></tr>
 *   <tr><td>{@link #EXCHANGE}</td><td>兑换单 id</td><td>V3 预留</td></tr>
 *   <tr><td>{@link #MANUAL}</td><td>{@code null}</td>
 *       <td>手工调整无天然单据，幂等改由 {@code point_record.request_id} 保证</td></tr>
 *   <tr><td>{@link #REWARD_PUNISH}</td><td>{@code honor_reward_punish.id}</td><td>V2 第 5 批启用</td></tr>
 * </table>
 *
 * <p><b>新增来源时必须分配新码，不要复用</b>——{@code uk_source} 只认 (source_type, source_id) 这一对，
 * 两张不同的表共用同一个 source_type，自增 id 必然撞车。奖惩之所以单独占 6 而不蹭 {@link #CORRECTION}，
 * 就是这个原因。</p>
 *
 * <p>{@code PointService.record} 的载荷复核**只能发现载荷不同的撞键**：两张单据恰好属于同一志愿者、
 * 金额又相同时，复核认不出它们是两笔，仍会当成重放而丢掉后一笔。**所以来源码独占是正确性的必要条件，
 * 复核只是发现问题的概率性手段，不是兜底。**分配新来源时不要因为「反正有复核」而心存侥幸。</p>
 *
 * @author hengde
 */
public final class PointSourceType {

    private PointSourceType() {
    }

    /** 活动积分（秘书部确认后发放 / 活动补录落账） */
    public static final int ACTIVITY = 1;
    /** 积分修正（组织部申请改积分 + 部长审核通过后的差额） */
    public static final int CORRECTION = 2;
    /** 勋章奖励（V2 第 3 批） */
    public static final int MEDAL = 3;
    /** 兑换消费（V3 积分商城） */
    public static final int EXCHANGE = 4;
    /** 管理员手工调整 */
    public static final int MANUAL = 5;
    /** 奖惩调整（V2 第 5 批奖惩中心）；独占一码，勿与 {@link #CORRECTION} 合并 */
    public static final int REWARD_PUNISH = 6;

    /** 操作方：系统自动 */
    public static final int OPERATOR_SYSTEM = 0;
    /** 操作方：管理员 */
    public static final int OPERATOR_ADMIN = 1;

    /**
     * 「消费类」来源码集合——{@link #isConsumption} 与排行榜的「累计获得」聚合 SQL 共用这一份。
     *
     * <p>之所以立成集合而不是让两边各写一次 {@code source_type <> 4}：积分榜排的是<b>累计获得</b>
     * （{@code PointSummaryVO.totalEarned} 的口径，即「非消费来源之和」）。将来消费类再添一种，
     * 若 SQL 那份没跟着改，同一个人在积分中心看到的总积分和在排行榜上的分数就会不一致——
     * 正是本项目反复强调的「两套口径显示两个数」。</p>
     */
    public static final java.util.List<Integer> CONSUMPTION_TYPES = java.util.List.of(EXCHANGE);

    /**
     * 该来源是否属于「消费」——即需求里「已使用积分」统计的口径。
     *
     * <p><b>消费按来源判定，不按正负判定。</b>负数流水不等于消费：把活动积分从 10 修正为 5 会产生一笔 -5，
     * 但志愿者并没有花掉 5 分，若按正负统计，积分中心会显示「已使用 5 分」——凭空捏造了一笔消费。
     * 反过来，先加后减的修正也会把「累计获得」虚高。故修正 / 手工调整 / 奖惩一律计入<b>获得的净额</b>
     * （修正本就是在更正当初发多了或发少了），只有真正花出去的才算已使用。</p>
     *
     * <p>当前只有兑换属消费，且 V3 才开放；本方法先立起来，是为了让口径有唯一出处，
     * 免得届时又在 SQL 里按正负拍一个。</p>
     *
     * @param sourceType 来源类型码
     * @return true=消费类
     */
    public static boolean isConsumption(Integer sourceType) {
        return sourceType != null && CONSUMPTION_TYPES.contains(sourceType);
    }

    /**
     * 来源类型中文名，供出参直接展示（前端不必再维护一份映射）。
     *
     * @param sourceType 来源类型码
     * @return 中文名；未知码返回「其他」而非抛异常——展示层不应因脏数据而整页失败
     */
    public static String labelOf(Integer sourceType) {
        if (sourceType == null) {
            return "其他";
        }
        return switch (sourceType) {
            case ACTIVITY -> "活动积分";
            case CORRECTION -> "积分修正";
            case MEDAL -> "勋章奖励";
            case EXCHANGE -> "兑换消费";
            case MANUAL -> "管理员调整";
            case REWARD_PUNISH -> "奖惩调整";
            default -> "其他";
        };
    }
}
