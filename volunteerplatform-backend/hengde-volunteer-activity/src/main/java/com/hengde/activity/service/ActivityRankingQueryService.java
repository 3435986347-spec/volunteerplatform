package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hengde.activity.constant.ActivityStatus;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.constant.SecretaryStatus;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.PointRecordMapper;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.PointRecord;
import com.hengde.activity.vo.RankingRowView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 活动域排行数据源（只读）：供 honor 域做「活动次数 / 活动时长 / 积分」三个榜单。
 *
 * <p>与 {@link ActivityStatsService} 同一套设计立场——<b>「什么算一次参与」「时长算到哪一分钟」
 * 「积分怎么才叫获得」这些活动域语义收在本服务内，不外泄给 honor 去拼条件</b>。honor 只负责排序、
 * 定名次、存快照；它一旦自己写条件，口径就会和积分中心/数据看板漂开。</p>
 *
 * <p><b>三个榜单的口径</b>（与既有展示口径逐条对齐，不新造）：</p>
 * <ul>
 *   <li><b>次数</b>——已发布/已结束活动上有签到记录的<b>活动数</b>（{@code COUNT(DISTINCT activity_id)}），
 *       与报名门槛「已参加活动次数」及志愿者资料页 {@code activityCount} 同口径。
 *       <b>注意不是</b> {@link ActivityStatsService#countParticipations}——那是<b>人次</b>，
 *       V30 起按场次计（一人报两场并各自签到＝2 人次，但仍只是 1 次活动）；</li>
 *   <li><b>时长</b>——同上范围内、秘书部已确认（{@code secretary_status=1}）的 {@code service_minutes} 之和，
 *       与 {@link ActivityStatsService#sumConfirmedServiceMinutes} 同口径；</li>
 *   <li><b>积分</b>——{@code point_record} 里<b>非消费来源</b>的 {@code change_amount} 之和，
 *       即 {@code PointSummaryVO.totalEarned}（「累计获得」）的口径。<b>不是余额</b>：
 *       V3 商城上线后，花掉积分不应让人在榜上掉名次——榜单奖励的是贡献而非结余。
 *       消费码集合取自 {@link PointSourceType#CONSUMPTION_TYPES}，与积分中心共用同一份定义。</li>
 * </ul>
 *
 * <p><b>时间区间的语义</b>：月榜/年榜按<b>服务发生的时间</b>切分，不按录入或确认时间——
 * 考勤取 {@code check_in_time}，积分取 {@code point_record.create_time}。
 * 传 {@code null} 表示不限（总榜）。区间为<b>左闭右开</b> {@code [from, to)}，
 * 避免按月切分时月末最后一秒的记录被两个月重复统计。</p>
 *
 * <p><b>为什么时长榜也要求 check_in_time 非空</b>：一条记录若没有签到时间，就无法归属到任何月份，
 * 只会出现在总榜而在月榜里凭空消失，两个榜加不起来。实际上不会丢数据——秘书部确认过的考勤必有签到时间
 * （自助签到与补录落账都会写入，后者取 slot 起止），故该条件对已确认行是恒真的。</p>
 *
 * @author hengde
 */
@Service
public class ActivityRankingQueryService {

    /**
     * 「真实活动」的 id 子查询：已发布 + 已结束（含历史活动）。与 {@link ActivityStatsService} 保持一致——
     * 草稿/待审/驳回/已取消活动上的脏签到不该把人送上榜。
     * is_deleted 手动过滤（子查询走原生 SQL，不经 MP 的 {@code @TableLogic} 自动追加）。
     */
    private static final String REAL_ACTIVITY_IDS =
            "SELECT id FROM activity WHERE status IN (" + ActivityStatus.PUBLISHED + ", "
                    + ActivityStatus.FINISHED + ") AND is_deleted = 0";

    private ActivityAttendanceMapper attendanceMapper;
    private PointRecordMapper pointRecordMapper;

    @Autowired
    public void setAttendanceMapper(ActivityAttendanceMapper attendanceMapper) {
        this.attendanceMapper = attendanceMapper;
    }

    @Autowired
    public void setPointRecordMapper(PointRecordMapper pointRecordMapper) {
        this.pointRecordMapper = pointRecordMapper;
    }

    /**
     * 活动次数榜。
     *
     * @param from  起（含），null=不限
     * @param to    止（不含），null=不限
     * @param limit 取前 N
     * @return 按次数降序、同分按志愿者 id 升序；无数据返回空列表
     */
    public List<RankingRowView> topByAttendanceCount(LocalDateTime from, LocalDateTime to, int limit) {
        QueryWrapper<ActivityAttendance> qw = new QueryWrapper<ActivityAttendance>()
                // COUNT(DISTINCT activity_id)：一人在同一活动报两场并各自签到，仍只算「1 次活动」。
                //
                // 【为什么必须显式 DISTINCT】V30 之前这里写的是 COUNT(*)，靠 activity_attendance 上的
                // uk_activity_volunteer(activity_id, volunteer_id) 保证「一人一活动至多一行」而恰好等价；
                // V30 把唯一键换成 (activity_id, volunteer_id, slot_id) 后该前提消失，
                // COUNT(*) 会不动一行榜单代码就悄悄变成「场次数榜」。那个唯一键是隐性承重的。
                //
                // 【口径依据】「活动次数」全站已有定义，本处只是跟随，不新造：
                //   · ActivityEnrollmentMapper#countDistinctJoinedActivities —— 报名门槛「已参加活动次数」，
                //     COUNT(DISTINCT activity_id)，注释写明「同一活动报多个时间段…只算 1 场」；
                //   · ServiceRecordService#batchStatsByVolunteerIds —— 志愿者资料页 activityCount，按 activity_id 入 Set 去重。
                // 若此处按场次计，同一个人在资料页显示「参加 3 个活动」、在榜单上却按 7 排名。
                //
                // 注意与「人次」区分：ActivityStatsService#countParticipations 是<b>人次</b>（＝参加了几场，
                // 依据原型 P97），V30 起有意按场次计，与本榜不同口径，不要互相对齐。
                .select("volunteer_id AS volunteerId", "COUNT(DISTINCT activity_id) AS metricValue")
                .isNotNull("check_in_time")
                .inSql("activity_id", REAL_ACTIVITY_IDS);
        applyRange(qw, "check_in_time", from, to);
        return aggregate(attendanceMapper.selectMaps(finishGrouping(qw, limit)));
    }

    /**
     * 活动时长榜（分钟）。
     *
     * @param from  起（含），null=不限
     * @param to    止（不含），null=不限
     * @param limit 取前 N
     * @return 按时长降序、同分按志愿者 id 升序；无数据返回空列表
     */
    public List<RankingRowView> topByServiceMinutes(LocalDateTime from, LocalDateTime to, int limit) {
        QueryWrapper<ActivityAttendance> qw = new QueryWrapper<ActivityAttendance>()
                .select("volunteer_id AS volunteerId", "COALESCE(SUM(service_minutes), 0) AS metricValue")
                .eq("secretary_status", SecretaryStatus.CONFIRMED)
                .isNotNull("check_in_time")
                .inSql("activity_id", REAL_ACTIVITY_IDS);
        applyRange(qw, "check_in_time", from, to);
        return aggregate(attendanceMapper.selectMaps(finishGrouping(qw, limit)));
    }

    /**
     * 积分榜（累计获得）。
     *
     * @param from  起（含），null=不限
     * @param to    止（不含），null=不限
     * @param limit 取前 N
     * @return 按积分降序、同分按志愿者 id 升序；无数据返回空列表
     */
    public List<RankingRowView> topByEarnedPoints(LocalDateTime from, LocalDateTime to, int limit) {
        QueryWrapper<PointRecord> qw = new QueryWrapper<PointRecord>()
                .select("volunteer_id AS volunteerId", "COALESCE(SUM(change_amount), 0) AS metricValue")
                .notIn("source_type", PointSourceType.CONSUMPTION_TYPES);
        applyRange(qw, "create_time", from, to);
        return aggregate(pointRecordMapper.selectMaps(finishGrouping(qw, limit)));
    }

    // ---------- 单人总量（供勋章「获取进度」） ----------

    /**
     * 某人的活动<b>总次数</b>（不限时间），口径与 {@link #topByAttendanceCount} 完全一致。
     *
     * <p><b>为什么勋章进度不能复用 {@code ServiceRecordService.batchStatsByVolunteerIds}</b>：
     * 那个方法是给「志愿者管理」列表看的粗口径，既不过滤活动状态、也不要求有签到时间——
     * 草稿 / 待审 / 已驳回 / 已取消活动上的脏考勤，乃至根本没签到的行，都会把进度顶上去。
     * 排行榜和数据看板都只认「已发布 / 已结束活动上的真实签到」，勋章进度要跟它们对齐，
     * 否则同一个人在排行榜上是 3 次、在勋章进度里是 5 次。</p>
     *
     * @param volunteerId 志愿者 id
     * @return 次数；id 为 null 返回 0
     */
    public long totalAttendanceCount(Long volunteerId) {
        if (volunteerId == null) {
            return 0L;
        }
        // 必须与 topByAttendanceCount 逐字同写法：COUNT(DISTINCT activity_id)。
        // V30 删掉 uk_activity_volunteer 后 COUNT(*) 不再等价于去重（详见 topByAttendanceCount 内注释），
        // 只改榜单不改这里，就会正好造出上面 javadoc 警告的那个偏差——榜上 3 次、勋章进度 5 次。
        return single(attendanceMapper.selectMaps(new QueryWrapper<ActivityAttendance>()
                .select("COUNT(DISTINCT activity_id) AS metricValue")
                .eq("volunteer_id", volunteerId)
                .isNotNull("check_in_time")
                .inSql("activity_id", REAL_ACTIVITY_IDS)));
    }

    /**
     * 某人的<b>已确认服务时长</b>总分钟数（不限时间），口径与 {@link #topByServiceMinutes} 完全一致。
     *
     * @param volunteerId 志愿者 id
     * @return 分钟数；id 为 null 返回 0
     * @see #totalAttendanceCount 关于「为什么不复用服务记录的粗口径」
     */
    public long totalServiceMinutes(Long volunteerId) {
        if (volunteerId == null) {
            return 0L;
        }
        return single(attendanceMapper.selectMaps(new QueryWrapper<ActivityAttendance>()
                .select("COALESCE(SUM(service_minutes), 0) AS metricValue")
                .eq("volunteer_id", volunteerId)
                .eq("secretary_status", SecretaryStatus.CONFIRMED)
                .isNotNull("check_in_time")
                .inSql("activity_id", REAL_ACTIVITY_IDS)));
    }

    // ---------- helpers ----------

    /** 取聚合单行单列；无 group by 时 COUNT/SUM 恒返回一行。 */
    private long single(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return 0L;
        }
        Object metric = rows.get(0).get("metricValue");
        return metric == null ? 0L : ((Number) metric).longValue();
    }

    /** 左闭右开 [from, to)；两端均可为 null 表示不限。 */
    private <T> void applyRange(QueryWrapper<T> qw, String column, LocalDateTime from, LocalDateTime to) {
        qw.ge(from != null, column, from).lt(to != null, column, to);
    }

    /**
     * 收尾：按人分组 + 定序 + 截断。
     *
     * <p>同分时<b>再按 volunteer_id 升序</b>——否则 MySQL 对并列行的返回顺序不保证稳定，
     * 同一个周期重跑快照会得到不同的名次，而快照的意义恰恰是把历史名次冻结住。</p>
     */
    private <T> QueryWrapper<T> finishGrouping(QueryWrapper<T> qw, int limit) {
        return qw.groupBy("volunteer_id")
                .orderByDesc("metricValue")
                .orderByAsc("volunteer_id")
                .last("LIMIT " + limit);
    }

    private List<RankingRowView> aggregate(List<Map<String, Object>> rows) {
        List<RankingRowView> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object volunteerId = row.get("volunteerId");
            Object metric = row.get("metricValue");
            if (volunteerId == null) {
                continue;
            }
            list.add(new RankingRowView(((Number) volunteerId).longValue(),
                    metric == null ? 0L : ((Number) metric).longValue()));
        }
        return list;
    }
}
