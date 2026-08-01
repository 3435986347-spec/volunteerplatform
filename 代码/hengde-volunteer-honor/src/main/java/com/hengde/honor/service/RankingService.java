package com.hengde.honor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.service.ActivityRankingQueryService;
import com.hengde.activity.vo.RankingRowView;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.honor.config.HonorProperties;
import com.hengde.honor.constant.RankPeriodType;
import com.hengde.honor.constant.RankType;
import com.hengde.honor.dao.HonorRankingSnapshotBatchMapper;
import com.hengde.honor.dao.HonorRankingSnapshotMapper;
import com.hengde.honor.entity.HonorRankingSnapshot;
import com.hengde.honor.entity.HonorRankingSnapshotBatch;
import com.hengde.honor.support.RankingPeriod;
import com.hengde.honor.vo.RankingEntryVO;
import com.hengde.honor.vo.RankingVO;
import com.hengde.honor.vo.SnapshotResultVO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 排行榜：活动次数 / 活动时长 / 积分 × 月 / 年 / 总。
 *
 * <p><b>核心设计：当期实时聚合，往期读冻结快照。</b>需求要一个下拉框能回看历史月份的排行，
 * 而志愿者的历史数据事后是会变的——活动补录、考勤修正、积分手工调整都会改动往月的底数。
 * 若历史月份也实时聚合，「2026 年 7 月排行」今天看和下个月看名次不一样，下拉框就失去意义。
 * 故已结束的周期把名次冻成快照，之后只读不算。</p>
 *
 * <p><b>总榜是例外</b>：它指「截至此刻的全部累计」，不存在「历史的总榜」，故恒为实时聚合、从不快照。</p>
 *
 * <p><b>三个榜单的口径不在本服务里定义</b>——「什么算一次参与」「时长算到哪一分钟」「积分怎么才算获得」
 * 全部收在 activity 域的 {@link ActivityRankingQueryService}。honor 只负责排序、定名次、存快照。
 * 这是为了避免同一个人在积分中心与排行榜上看到两个不同的数（本项目反复踩过的「两套口径」）。</p>
 *
 * <p><b>并列名次的处理</b>：指标相同时按 volunteerId 升序，名次仍然连续（1、2、3…）而非并列同名次。
 * 定序规则写在 activity 域的聚合 SQL 里，保证同一份数据重算出的名次完全一致——
 * 否则快照重跑会得到不同结果，冻结也就没了意义。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class RankingService {

    /** 查询默认取前 50 名 */
    public static final int DEFAULT_LIMIT = 50;

    /** 查询上限，与项目分页 size 上限一致 */
    public static final int MAX_LIMIT = 100;

    /**
     * 查不到姓名时的占位。
     *
     * <p>榜单展示的是<b>历史贡献</b>，志愿者事后注销不该让那段历史从榜上消失（尤其快照已冻结的往期），
     * 故保留行、只把姓名换成占位。游客不可能有考勤或积分流水，正常情况下不会走到这里。</p>
     */
    private static final String UNKNOWN_VOLUNTEER = "已注销志愿者";

    /**
     * 快照生成的锁前缀（按「周期类型:周期标识」上锁）。
     *
     * <p>与既有的 {@code lock:enroll:volunteer:} / {@code lock:group:volunteer:} /
     * {@code lock:point:volunteer:} 互不抢占。</p>
     */
    private static final String LOCK_PREFIX = "lock:honor:ranking-snapshot:";

    private HonorRankingSnapshotMapper snapshotMapper;
    private HonorRankingSnapshotBatchMapper batchMapper;
    private RedissonClient redissonClient;
    private ActivityRankingQueryService activityRankingQueryService;
    private VolunteerQueryService volunteerQueryService;
    private HonorProperties honorProperties;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setSnapshotMapper(HonorRankingSnapshotMapper snapshotMapper) {
        this.snapshotMapper = snapshotMapper;
    }

    @Autowired
    public void setBatchMapper(HonorRankingSnapshotBatchMapper batchMapper) {
        this.batchMapper = batchMapper;
    }

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Autowired
    public void setActivityRankingQueryService(ActivityRankingQueryService activityRankingQueryService) {
        this.activityRankingQueryService = activityRankingQueryService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setHonorProperties(HonorProperties honorProperties) {
        this.honorProperties = honorProperties;
    }

    @Autowired
    public void setTransactionTemplate(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    // ================= 查询 =================

    /**
     * 取一张榜单。
     *
     * @param rankType   榜单 1次数/2时长/3积分
     * @param periodType 周期 1月/2年/3总
     * @param periodKey  周期标识：月 {@code 2026-07} / 年 {@code 2026}；总榜忽略
     * @param limit      取前 N，默认 {@value #DEFAULT_LIMIT}，上限 {@value #MAX_LIMIT}
     * @return 榜单；无人上榜时 entries 为空列表
     * @throws BusinessException 榜单类型或周期非法
     */
    public RankingVO ranking(Integer rankType, Integer periodType, String periodKey, Integer limit) {
        int type = requireAvailableRankType(rankType);
        RankingPeriod period = RankingPeriod.of(periodType, periodKey);
        int size = normalizeLimit(limit);

        // 是否读快照，**只看完成标记在不在**，绝不能看「快照表有没有行」——
        // 空榜是合法的冻结结果（那个月确实没人参加），按行数判定会让空榜永远退回实时聚合，
        // 事后补录一笔该月数据，这个「历史」月榜就会凭空冒出人来。
        boolean fromSnapshot = period.closed(LocalDateTime.now()) && isFrozen(period, type);
        List<RankingEntryVO> entries = fromSnapshot
                ? toEntriesFromSnapshot(readSnapshot(period, type, size))
                // 尚未冻结（本功能上线前的历史月份、或冷静期内）——退回实时聚合。
                // 返回空榜单更「安全」，但对用户表现为「那个月没有人参加活动」，是把缺功能伪装成缺数据。
                : toEntriesFromLive(aggregate(type, period, size));
        return buildVO(type, period, fromSnapshot, entries);
    }

    // ================= 快照 =================

    /**
     * 生成（或强制重算）某个已结束周期的快照，三个榜单一次全做。
     *
     * <p><b>幂等</b>：非强制模式下，已有快照的板块直接跳过——这正是「冻结」的含义，
     * 也让定时任务可以每天安全地重跑。强制模式会先物理删除再重写，
     * 用于历史数据补录后管理员主动认可「这个月的名次该按新数据重排」。</p>
     *
     * <p>用 {@link TransactionTemplate} 而非 {@code @Transactional}：本方法会被同类的
     * {@link #generateClosedPeriodSnapshots()} 调用，自调用不走代理，注解式事务会<b>静默失效</b>，
     * 那样「三个榜单要么全有要么全无」的原子性就没了。</p>
     *
     * <p><b>按周期上分布式锁</b>：{@code @Scheduled} 是<b>进程内</b>调度，API 若横向扩成多实例，
     * 每个实例都会在同一时刻跑同一个周期；管理员的手工补跑也可能与定时任务撞上。</p>
     *
     * <p><b>这把锁不是数据正确性的唯一防线，别高估它</b>——已实测：把锁键换成每次调用都不同（等于没锁），
     * 两个 {@code force=true} 并发补跑仍然得到完整正确的榜单，因为「先删后写」在 InnoDB 里本就被行锁串行了
     * （后到的 DELETE 会等前一个事务提交）。锁真正买到的是另外两件事：
     * ① 把并发补跑之间<b>可能的间隙锁死锁</b>（双方各持间隙锁、又都要在该间隙插入）变成一次干净的等待；
     * ② 多实例下省掉重复的全量聚合。所以它值得留着，但<b>不要因为有它就认为并发路径已被测试覆盖</b>。</p>
     *
     * <p>锁在<b>事务之外</b>获取，遵循项目既有纪律。</p>
     *
     * @param periodType 周期 1月/2年（总榜不支持）
     * @param periodKey  周期标识
     * @param force      true=覆盖已有快照（会改写已公示的历史名次）
     * @return 生成结果
     * @throws BusinessException 周期非法、是总榜、或周期尚未结束
     */
    public SnapshotResultVO generateSnapshot(Integer periodType, String periodKey, boolean force) {
        RankingPeriod period = RankingPeriod.of(periodType, periodKey);
        if (period.periodType() == RankPeriodType.TOTAL) {
            throw new BusinessException("总榜恒为当期实时榜，不生成快照");
        }
        if (!period.closed(LocalDateTime.now())) {
            throw new BusinessException("周期尚未结束，不能冻结名次：" + period.periodKey());
        }

        // 配置守卫：topN 若被配成 0，aggregate 就是 LIMIT 0、返回空，writeSnapshot 写 0 行，
        // 而 writeBatch 照样打上冻结标记——一张本来有人的榜单被冻成空榜。最糟的是**下游无从察觉**：
        // 空榜本身是合法的冻结结果（那个周期确实没人参加，这正是完成标记表存在的理由），
        // 没有任何信号能区分「真没人」与「配错了」。
        // 负数会让 MySQL 报 LIMIT -1 语法错（响亮地失败），0 才是那个安静的坑，故统一拦在 < 1。
        //
        // 放在这里是因为「便宜且清晰」——不必先拿分布式锁、再开事务、再回滚，失败点离原因最近。
        // **但别把它说成正确性的必要位置**：下面的两个 DELETE 都在 TransactionTemplate 内，
        // 守卫即便挪到 DELETE 之后，只要仍然抛异常，事务一样会整体回滚、旧快照毫发无损。
        // 真正的不变量是「配置非法时不得提交任何改动」，用例钉的也是这一条，而非源码行的先后。
        int topN = honorProperties.getRanking().getSnapshotTopN();
        if (topN < 1) {
            throw new BusinessException("排行榜快照名次数配置非法（hengde.honor.ranking.snapshot-top-n = "
                    + topN + "），须 ≥ 1；已中止，未改动任何既有快照");
        }
        List<String> skipped = new ArrayList<>();
        List<String> frozen = new ArrayList<>();
        Integer written = DistributedLockSupport.runLocked(redissonClient,
                LOCK_PREFIX + period.periodType() + ":" + period.periodKey(),
                () -> transactionTemplate.execute(status -> {
                    int count = 0;
                    for (Integer type : RankType.AVAILABLE) {
                        if (!force && isFrozen(period, type)) {
                            skipped.add(RankType.labelOf(type));
                            continue;
                        }
                        snapshotMapper.deletePeriod(period.periodType(), period.periodKey(), type);
                        batchMapper.deleteBatch(period.periodType(), period.periodKey(), type);
                        int rows = writeSnapshot(period, type, aggregate(type, period, topN));
                        // 完成标记必须与快照行<b>同事务</b>写入：先写行后崩会留下「有行但未标记完成」，
                        // 下次重跑照样覆盖，无害；反之若先标记后写行，中途失败就会把一份残缺榜单锁死成「已冻结」
                        writeBatch(period, type, rows);
                        frozen.add(RankType.labelOf(type));
                        count += rows;
                    }
                    return count;
                }));

        SnapshotResultVO vo = new SnapshotResultVO();
        vo.setPeriodType(period.periodType());
        vo.setPeriodTypeLabel(RankPeriodType.labelOf(period.periodType()));
        vo.setPeriodKey(period.periodKey());
        vo.setWritten(written == null ? 0 : written);
        vo.setFrozen(frozen);
        vo.setSkipped(skipped);
        vo.setForced(force);
        log.info("排行榜快照生成完成：{} {} 冻结 {} 个板块（写入 {} 行），跳过 {}，force={}",
                RankPeriodType.labelOf(period.periodType()), period.periodKey(),
                frozen.size(), vo.getWritten(), skipped, force);
        return vo;
    }

    /**
     * 定时任务入口：把<b>已过冷静期</b>的上月、上年快照补齐。
     *
     * <p>每次跑都尝试上月与上年两个周期，已冻结的会被跳过，因此可以每天安全重跑——
     * 漏跑一天次日自动补上，不像「每月 1 日触发」那样一旦当天服务不在线就永久缺失。</p>
     *
     * <p>两个周期<b>各自独立</b>：一个失败不影响另一个，异常在此吞掉并记日志，
     * 免得定时线程因单次失败而中断后续调度。</p>
     *
     * @return 实际写入了快照的周期数（已跳过的不计）
     */
    public int generateClosedPeriodSnapshots() {
        LocalDate today = LocalDate.now();
        int generated = 0;
        generated += freezeIfDue(RankPeriodType.MONTH, RankingPeriod.previousMonthKey(today));
        generated += freezeIfDue(RankPeriodType.YEAR, RankingPeriod.previousYearKey(today));
        return generated;
    }

    private int freezeIfDue(int periodType, String periodKey) {
        try {
            RankingPeriod period = RankingPeriod.of(periodType, periodKey);
            LocalDateTime freezeAt = period.to().plusDays(honorProperties.getRanking().getFreezeDelayDays());
            if (freezeAt.isAfter(LocalDateTime.now())) {
                return 0;
            }
            // 按「本次新冻结了几个板块」计数，不按写入行数——空榜也是一次有效的冻结，
            // 若按行数计，无人参加的月份会被当成「没冻结成功」而每天重跑
            return generateSnapshot(periodType, periodKey, false).getFrozen().isEmpty() ? 0 : 1;
        } catch (Exception e) {
            log.error("生成排行榜快照失败：periodType={} periodKey={}", periodType, periodKey, e);
            return 0;
        }
    }

    // ================= helpers =================

    private int requireAvailableRankType(Integer rankType) {
        if (rankType != null && rankType == RankType.WISH) {
            // 有码没数据源。放行只会静默产出空榜单，把「功能未开放」伪装成「没人上榜」。
            throw new BusinessException("微心愿排行尚未开放");
        }
        if (rankType == null || !RankType.AVAILABLE.contains(rankType)) {
            throw new BusinessException("未知的榜单类型");
        }
        return rankType;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private List<RankingRowView> aggregate(int rankType, RankingPeriod period, int limit) {
        return switch (rankType) {
            case RankType.ATTENDANCE_COUNT ->
                    activityRankingQueryService.topByAttendanceCount(period.from(), period.to(), limit);
            case RankType.SERVICE_MINUTES ->
                    activityRankingQueryService.topByServiceMinutes(period.from(), period.to(), limit);
            case RankType.POINTS ->
                    activityRankingQueryService.topByEarnedPoints(period.from(), period.to(), limit);
            default -> throw new BusinessException("未知的榜单类型");
        };
    }

    private List<HonorRankingSnapshot> readSnapshot(RankingPeriod period, int rankType, int limit) {
        return snapshotMapper.selectList(Wrappers.<HonorRankingSnapshot>lambdaQuery()
                .eq(HonorRankingSnapshot::getPeriodType, period.periodType())
                .eq(HonorRankingSnapshot::getPeriodKey, period.periodKey())
                .eq(HonorRankingSnapshot::getRankType, rankType)
                .orderByAsc(HonorRankingSnapshot::getRankNo)
                .last("LIMIT " + limit));
    }

    /**
     * 该「周期 × 板块」是否已冻结。
     *
     * <p><b>只看完成标记，不看快照行数</b>——理由见 {@code HonorRankingSnapshotBatch}：
     * 空榜（0 行）同样是合法的冻结结果，按行数判定会让空榜永远冻不住。</p>
     */
    private boolean isFrozen(RankingPeriod period, int rankType) {
        return batchMapper.countBatch(period.periodType(), period.periodKey(), rankType) > 0;
    }

    private void writeBatch(RankingPeriod period, int rankType, int rowCount) {
        HonorRankingSnapshotBatch batch = new HonorRankingSnapshotBatch();
        batch.setPeriodType(period.periodType());
        batch.setPeriodKey(period.periodKey());
        batch.setRankType(rankType);
        batch.setRowCount(rowCount);
        batchMapper.insert(batch);
    }

    private int writeSnapshot(RankingPeriod period, int rankType, List<RankingRowView> rows) {
        int rankNo = 1;
        for (RankingRowView row : rows) {
            HonorRankingSnapshot entity = new HonorRankingSnapshot();
            entity.setPeriodType(period.periodType());
            entity.setPeriodKey(period.periodKey());
            entity.setRankType(rankType);
            entity.setVolunteerId(row.getVolunteerId());
            entity.setRankNo(rankNo++);
            entity.setMetricValue(row.getMetricValue());
            snapshotMapper.insert(entity);
        }
        return rows.size();
    }

    private List<RankingEntryVO> toEntriesFromLive(List<RankingRowView> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (RankingRowView row : rows) {
            ids.add(row.getVolunteerId());
        }
        Map<Long, String> names = resolveNames(ids);
        List<RankingEntryVO> entries = new ArrayList<>(rows.size());
        int rankNo = 1;
        for (RankingRowView row : rows) {
            entries.add(toEntry(rankNo++, row.getVolunteerId(), row.getMetricValue(), names));
        }
        return entries;
    }

    private List<RankingEntryVO> toEntriesFromSnapshot(List<HonorRankingSnapshot> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (HonorRankingSnapshot row : rows) {
            ids.add(row.getVolunteerId());
        }
        Map<Long, String> names = resolveNames(ids);
        List<RankingEntryVO> entries = new ArrayList<>(rows.size());
        for (HonorRankingSnapshot row : rows) {
            // 名次取<b>存下来的</b>值，不按下标重排——冻结的就是这个数
            entries.add(toEntry(row.getRankNo(), row.getVolunteerId(), row.getMetricValue(), names));
        }
        return entries;
    }

    private RankingEntryVO toEntry(Integer rankNo, Long volunteerId, Long metricValue, Map<Long, String> names) {
        RankingEntryVO vo = new RankingEntryVO();
        vo.setRankNo(rankNo);
        vo.setVolunteerId(volunteerId);
        vo.setVolunteerName(names.getOrDefault(volunteerId, UNKNOWN_VOLUNTEER));
        vo.setMetricValue(metricValue == null ? 0L : metricValue);
        return vo;
    }

    private Map<Long, String> resolveNames(Set<Long> ids) {
        return ids.isEmpty() ? Map.of() : volunteerQueryService.listNamesByIds(ids);
    }

    private RankingVO buildVO(int rankType, RankingPeriod period, boolean fromSnapshot,
                              List<RankingEntryVO> entries) {
        RankingVO vo = new RankingVO();
        vo.setRankType(rankType);
        vo.setRankTypeLabel(RankType.labelOf(rankType));
        vo.setUnit(RankType.unitOf(rankType));
        vo.setPeriodType(period.periodType());
        vo.setPeriodTypeLabel(RankPeriodType.labelOf(period.periodType()));
        vo.setPeriodKey(period.periodKey());
        vo.setFromSnapshot(fromSnapshot);
        vo.setEntries(entries);
        return vo;
    }
}
