package com.hengde.honor;

import com.hengde.activity.constant.ActivityStatus;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.constant.SecretaryStatus;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.dao.PointRecordMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.entity.PointRecord;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.WishFlow;
import com.hengde.donate.dao.DonateWishClaimMapper;
import com.hengde.donate.entity.DonateWishClaim;
import com.hengde.honor.config.HonorProperties;
import com.hengde.honor.constant.RankPeriodType;
import com.hengde.honor.constant.RankType;
import com.hengde.honor.dao.HonorRankingSnapshotBatchMapper;
import com.hengde.honor.dao.HonorRankingSnapshotMapper;
import com.hengde.honor.entity.HonorRankingSnapshot;
import com.hengde.honor.entity.HonorRankingSnapshotBatch;
import com.hengde.honor.job.RankingSnapshotJob;
import com.hengde.honor.service.RankingService;
import com.hengde.honor.support.RankingPeriod;
import com.hengde.honor.vo.RankingEntryVO;
import com.hengde.honor.vo.RankingVO;
import com.hengde.honor.vo.SnapshotResultVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排行榜验证。<b>需本机 Docker</b>（MySQL + Redis——经活动域传递引入 redisson starter）。
 *
 * <p><b>用例间的隔离靠「各占一个历史月份」</b>：排行是全库聚合，若都用当前时间，
 * 前一个用例造的人会挤进后一个用例的榜单，断言只能写成「包含」而失去精度。
 * 每个用例挑一个互不重叠的 2001 年月份，月榜里就只有它自己的数据，可以写绝对断言。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class RankingServiceTest {

    private static final AtomicLong SEQ = new AtomicLong();

    private RankingService rankingService;
    private RankingSnapshotJob rankingSnapshotJob;
    private HonorProperties honorProperties;
    private VolunteerMapper volunteerMapper;
    private ActivityMapper activityMapper;
    @Autowired
    private ActivitySlotMapper slotMapper;
    @Autowired
    private DonateWishClaimMapper wishClaimMapper;
    private ActivityAttendanceMapper attendanceMapper;
    private PointRecordMapper pointRecordMapper;
    private HonorRankingSnapshotMapper snapshotMapper;
    private HonorRankingSnapshotBatchMapper batchMapper;

    @Autowired
    public void setSnapshotMapper(HonorRankingSnapshotMapper snapshotMapper) {
        this.snapshotMapper = snapshotMapper;
    }

    @Autowired
    public void setBatchMapper(HonorRankingSnapshotBatchMapper batchMapper) {
        this.batchMapper = batchMapper;
    }

    @Autowired
    public void setRankingService(RankingService rankingService) {
        this.rankingService = rankingService;
    }

    @Autowired
    public void setRankingSnapshotJob(RankingSnapshotJob rankingSnapshotJob) {
        this.rankingSnapshotJob = rankingSnapshotJob;
    }

    @Autowired
    public void setHonorProperties(HonorProperties honorProperties) {
        this.honorProperties = honorProperties;
    }

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    @Autowired
    public void setActivityMapper(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    @Autowired
    public void setAttendanceMapper(ActivityAttendanceMapper attendanceMapper) {
        this.attendanceMapper = attendanceMapper;
    }

    @Autowired
    public void setPointRecordMapper(PointRecordMapper pointRecordMapper) {
        this.pointRecordMapper = pointRecordMapper;
    }

    // ================= 三个榜单的口径 =================

    @Test
    void attendanceCount_ordersByCountAndBreaksTiesByVolunteerId() {
        LocalDateTime when = LocalDateTime.of(2001, 1, 15, 10, 0);
        Long top = volunteer("次数第一");
        Long tieEarly = volunteer("并列靠前");
        Long tieLate = volunteer("并列靠后");
        attend(top, when, 60, SecretaryStatus.CONFIRMED);
        attend(top, when, 60, SecretaryStatus.CONFIRMED);
        attend(top, when, 60, SecretaryStatus.CONFIRMED);
        attend(tieEarly, when, 60, SecretaryStatus.CONFIRMED);
        attend(tieEarly, when, 60, SecretaryStatus.CONFIRMED);
        attend(tieLate, when, 60, SecretaryStatus.CONFIRMED);
        attend(tieLate, when, 60, SecretaryStatus.CONFIRMED);

        List<RankingEntryVO> entries = month(RankType.ATTENDANCE_COUNT, "2001-01").getEntries();

        assertEquals(3, entries.size());
        assertEquals(top, entries.get(0).getVolunteerId());
        assertEquals(3L, entries.get(0).getMetricValue());
        assertEquals(1, entries.get(0).getRankNo());
        // 并列时按 volunteerId 升序——先建的 id 小、排在前。定序必须确定，否则快照重跑名次会变
        assertEquals(tieEarly, entries.get(1).getVolunteerId());
        assertEquals(tieLate, entries.get(2).getVolunteerId());
        assertEquals(2, entries.get(1).getRankNo(), "并列也给连续名次，不做并列同名次");
        assertEquals(3, entries.get(2).getRankNo());
    }

    @Test
    void serviceMinutes_countsOnlySecretaryConfirmed() {
        LocalDateTime when = LocalDateTime.of(2001, 2, 15, 10, 0);
        Long confirmed = volunteer("已确认时长");
        Long pending = volunteer("未确认时长");
        attend(confirmed, when, 90, SecretaryStatus.CONFIRMED);
        attend(pending, when, 600, SecretaryStatus.PENDING);

        List<RankingEntryVO> entries = month(RankType.SERVICE_MINUTES, "2001-02").getEntries();

        assertEquals(1, entries.size(), "未经秘书部确认的时长不上榜，哪怕分钟数更高");
        assertEquals(confirmed, entries.get(0).getVolunteerId());
        assertEquals(90L, entries.get(0).getMetricValue());
    }

    /**
     * 积分榜排的是<b>累计获得</b>而非余额。
     *
     * <p>这是本批最容易写错的一处：若按 {@code SUM(change_amount)}（余额）排，
     * 花掉积分的人会在榜上掉名次——榜单奖励的是贡献，不是结余。
     * 用例里 A 的余额低于 B、但获得高于 B，两种口径会给出<b>相反</b>的名次。</p>
     */
    @Test
    void points_rankByEarnedNotBalance() {
        LocalDateTime when = LocalDateTime.of(2001, 3, 15, 10, 0);
        Long spender = volunteer("挣得多花得多");
        Long saver = volunteer("挣得少没花");
        point(spender, 100, PointSourceType.ACTIVITY, when);
        point(spender, -40, PointSourceType.EXCHANGE, when);   // 消费：不冲减「累计获得」
        point(saver, 80, PointSourceType.ACTIVITY, when);

        List<RankingEntryVO> entries = month(RankType.POINTS, "2001-03").getEntries();

        assertEquals(2, entries.size());
        assertEquals(spender, entries.get(0).getVolunteerId(),
                "按余额排会是 saver(80) > spender(60)，按累计获得才是 spender(100) 第一");
        assertEquals(100L, entries.get(0).getMetricValue(), "兑换消费不冲减累计获得");
        assertEquals(saver, entries.get(1).getVolunteerId());
        assertEquals(80L, entries.get(1).getMetricValue());
    }

    /** 积分修正是「更正当初发多发少」，属获得的净额，要冲减；与消费的区别不在正负而在来源。 */
    @Test
    void points_correctionReducesEarnedButExchangeDoesNot() {
        LocalDateTime when = LocalDateTime.of(2001, 12, 15, 10, 0);
        Long corrected = volunteer("被修正");
        point(corrected, 100, PointSourceType.ACTIVITY, when);
        point(corrected, -30, PointSourceType.CORRECTION, when);

        List<RankingEntryVO> entries = month(RankType.POINTS, "2001-12").getEntries();

        assertEquals(1, entries.size());
        assertEquals(70L, entries.get(0).getMetricValue(), "修正的负数要冲减累计获得");
    }

    @Test
    void ranking_excludesAttendanceOnNonPublishedActivities() {
        LocalDateTime when = LocalDateTime.of(2001, 7, 15, 10, 0);
        Long onDraft = volunteer("草稿活动签到");
        Long onPublished = volunteer("正常活动签到");
        attendOn(activity(ActivityStatus.DRAFT), onDraft, when, 300, SecretaryStatus.CONFIRMED);
        attendOn(activity(ActivityStatus.PUBLISHED), onPublished, when, 10, SecretaryStatus.CONFIRMED);

        List<RankingEntryVO> entries = month(RankType.ATTENDANCE_COUNT, "2001-07").getEntries();

        assertEquals(1, entries.size(), "草稿/待审/驳回活动上的脏签到不该把人送上榜");
        assertEquals(onPublished, entries.get(0).getVolunteerId());
    }

    // ================= 周期切分 =================

    @Test
    void monthRange_isLeftClosedRightOpen() {
        Long inside = volunteer("月初零点");
        Long nextMonth = volunteer("次月零点");
        attend(inside, LocalDateTime.of(2001, 4, 1, 0, 0), 60, SecretaryStatus.CONFIRMED);
        attend(nextMonth, LocalDateTime.of(2001, 5, 1, 0, 0), 60, SecretaryStatus.CONFIRMED);

        List<RankingEntryVO> april = month(RankType.ATTENDANCE_COUNT, "2001-04").getEntries();
        List<RankingEntryVO> may = month(RankType.ATTENDANCE_COUNT, "2001-05").getEntries();

        assertEquals(1, april.size());
        assertEquals(inside, april.get(0).getVolunteerId(), "月初 00:00:00 属于本月（左闭）");
        assertEquals(1, may.size());
        assertEquals(nextMonth, may.get(0).getVolunteerId(), "次月 00:00:00 不属于本月（右开），不重复统计");
    }

    @Test
    void totalPeriod_ignoresTimeWindowAndNeverUsesSnapshot() {
        // 造一个远超其他用例量级的分数，好在全库总榜里稳定占据第一
        Long legend = volunteer("总榜第一");
        point(legend, 9_000_000, PointSourceType.ACTIVITY, LocalDateTime.of(1999, 1, 1, 0, 0));

        RankingVO total = rankingService.ranking(RankType.POINTS, RankPeriodType.TOTAL, null, 10);

        assertEquals(RankPeriodType.TOTAL_KEY, total.getPeriodKey());
        assertFalse(total.getFromSnapshot(), "总榜恒为实时聚合，永不读快照");
        assertEquals(legend, total.getEntries().get(0).getVolunteerId());
        assertTrue(month(RankType.POINTS, "2001-03").getEntries().stream()
                        .noneMatch(e -> legend.equals(e.getVolunteerId())),
                "1999 年的流水不该出现在 2001-03 的月榜里");
    }

    @Test
    void yearRanking_coversWholeYear() {
        Long v = volunteer("跨月累计");
        point(v, 11, PointSourceType.ACTIVITY, LocalDateTime.of(2002, 1, 5, 9, 0));
        point(v, 22, PointSourceType.ACTIVITY, LocalDateTime.of(2002, 11, 5, 9, 0));

        RankingVO year = rankingService.ranking(RankType.POINTS, RankPeriodType.YEAR, "2002", 10);

        assertEquals(1, year.getEntries().size());
        assertEquals(33L, year.getEntries().get(0).getMetricValue(), "年榜累计全年");
    }

    // ================= 快照：冻结与补跑 =================

    /**
     * 快照的<b>核心性质</b>：已结束周期的名次一旦冻结，后续数据变动不再改变它。
     *
     * <p>顺带验证 {@code deletePeriod} 必须是<b>物理</b>删除——强制重算那步若改成逻辑删除，
     * 旧行仍占着 {@code uk_period_rank_volunteer}，重写会撞唯一键直接失败。</p>
     */
    @Test
    void snapshot_freezesRanksAgainstLaterDataChange_andForceRecomputes() {
        LocalDateTime when = LocalDateTime.of(2001, 6, 15, 10, 0);
        Long leader = volunteer("原第一");
        Long chaser = volunteer("后来居上");
        attend(leader, when, 60, SecretaryStatus.CONFIRMED);
        attend(leader, when, 60, SecretaryStatus.CONFIRMED);
        attend(chaser, when, 60, SecretaryStatus.CONFIRMED);

        SnapshotResultVO generated = rankingService.generateSnapshot(RankPeriodType.MONTH, "2001-06", false);
        assertTrue(generated.getWritten() > 0, "应写入快照行");
        assertTrue(generated.getSkipped().isEmpty(), "首次生成不该有跳过");

        RankingVO frozen = month(RankType.ATTENDANCE_COUNT, "2001-06");
        assertTrue(frozen.getFromSnapshot(), "已结束且已冻结的周期应读快照");
        assertEquals(leader, frozen.getEntries().get(0).getVolunteerId());

        // 事后补录：实时口径下 chaser 已经反超（3 > 2）
        attend(chaser, when, 60, SecretaryStatus.CONFIRMED);
        attend(chaser, when, 60, SecretaryStatus.CONFIRMED);

        RankingVO stillFrozen = month(RankType.ATTENDANCE_COUNT, "2001-06");
        assertTrue(stillFrozen.getFromSnapshot());
        assertEquals(leader, stillFrozen.getEntries().get(0).getVolunteerId(),
                "历史名次已冻结，事后补录不得改变它");
        assertEquals(2L, stillFrozen.getEntries().get(0).getMetricValue(), "指标值也是冻结时的");

        // 管理员显式认可「按新数据重排」
        SnapshotResultVO forced = rankingService.generateSnapshot(RankPeriodType.MONTH, "2001-06", true);
        assertTrue(forced.getForced());
        RankingVO recomputed = month(RankType.ATTENDANCE_COUNT, "2001-06");
        assertTrue(recomputed.getFromSnapshot());
        assertEquals(chaser, recomputed.getEntries().get(0).getVolunteerId(), "强制重算后名次更新");
        assertEquals(3L, recomputed.getEntries().get(0).getMetricValue());
    }

    /**
     * <b>空榜也必须冻结得住</b>。
     *
     * <p>曾经的缺陷：是否读快照按「快照表有没有行」判定，于是无人上榜的周期永远被当成「还没冻结」，
     * 一直退回实时聚合；事后补录一笔该周期的数据，这个已经「冻结」过的历史月榜就凭空冒出人来。
     * 现改为只看 {@code honor_ranking_snapshot_batch} 里的完成标记。</p>
     */
    @Test
    void emptyPeriod_isFrozenToo_andLaterBackfillCannotLeakIn() {
        String emptyMonth = "1988-01";
        SnapshotResultVO result = rankingService.generateSnapshot(RankPeriodType.MONTH, emptyMonth, false);
        assertEquals(0, result.getWritten(), "该月无人上榜，写入 0 行");
        assertEquals(RankType.AVAILABLE.size(), result.getFrozen().size(),
                "写 0 行也是冻结——三个板块都应记为已冻结");

        RankingVO empty = month(RankType.POINTS, emptyMonth);
        assertTrue(empty.getFromSnapshot(), "空快照同样是快照，不该退回实时聚合");
        assertTrue(empty.getEntries().isEmpty());

        // 事后补录一笔该月积分：若冻结失效，它会漏进这张历史榜单
        Long latecomer = volunteer("迟到的补录");
        point(latecomer, 66, PointSourceType.ACTIVITY, LocalDateTime.of(1988, 1, 20, 10, 0));

        RankingVO stillEmpty = month(RankType.POINTS, emptyMonth);
        assertTrue(stillEmpty.getFromSnapshot());
        assertTrue(stillEmpty.getEntries().isEmpty(),
                "已冻结的空榜不得因事后补录而冒出人来");

        // 而强制补跑是管理员显式认可的重排，此时应当纳入
        rankingService.generateSnapshot(RankPeriodType.MONTH, emptyMonth, true);
        RankingVO recomputed = month(RankType.POINTS, emptyMonth);
        assertEquals(1, recomputed.getEntries().size(), "force 补跑后才纳入补录数据");
        assertEquals(latecomer, recomputed.getEntries().get(0).getVolunteerId());
    }

    @Test
    void emptyPeriodSnapshot_isIdempotent() {
        String emptyMonth = "1988-02";
        rankingService.generateSnapshot(RankPeriodType.MONTH, emptyMonth, false);
        SnapshotResultVO again = rankingService.generateSnapshot(RankPeriodType.MONTH, emptyMonth, false);

        assertTrue(again.getFrozen().isEmpty(), "已冻结的空周期不该被重复冻结");
        assertEquals(RankType.AVAILABLE.size(), again.getSkipped().size(),
                "空榜若不记冻结状态，定时任务会天天重跑它");
    }

    /**
     * {@code snapshotTopN} 配成 0 时必须拒绝冻结，而不是把有数据的榜单冻成空榜。
     *
     * <p>这是个<b>安静</b>的坑：{@code LIMIT 0} 聚合出空列表、写 0 行，完成标记却照样落下。
     * 而「空榜也是合法的冻结结果」正是本设计的既定语义（见 V26 抬头），
     * 于是下游<b>没有任何信号</b>能区分「那个月真没人」和「配错了」。
     * 负数会让 MySQL 报 {@code LIMIT -1} 语法错、响亮地失败，反倒不危险；0 才需要专门拦。</p>
     *
     * <p>第二段断言覆盖更坏的情况：{@code force=true} 会<b>先删旧快照再重写</b>，
     * 一次误配不得把已公示的历史名次抹成空榜。</p>
     *
     * <p><b>本用例证明的是「配置非法时不得提交任何改动」，不是「守卫必须写在 DELETE 之前」</b>——
     * 两个 DELETE 都在 {@code TransactionTemplate} 内，守卫挪到它们之后只要仍然抛异常，
     * 事务照样整体回滚，本用例依旧会绿。守卫写在前面是因为便宜且失败点离原因最近，
     * 不是因为写在后面就会破坏数据。</p>
     */
    @Test
    void snapshotTopN_belowOne_isRejectedAndFreezesNothing() {
        String periodKey = "2007-03";
        LocalDateTime when = LocalDateTime.of(2007, 3, 12, 10, 0);
        Long v = volunteer("配置守卫");
        attend(v, when, 90, SecretaryStatus.CONFIRMED);
        point(v, 70, PointSourceType.ACTIVITY, when);

        int original = honorProperties.getRanking().getSnapshotTopN();
        try {
            honorProperties.getRanking().setSnapshotTopN(0);
            assertThrows(BusinessException.class,
                    () -> rankingService.generateSnapshot(RankPeriodType.MONTH, periodKey, false),
                    "topN=0 应直接拒绝，而不是冻出一张空榜");

            RankingVO live = month(RankType.POINTS, periodKey);
            assertFalse(live.getFromSnapshot(), "拒绝之后该周期不得被标记为已冻结");
            assertEquals(1, live.getEntries().size(), "未冻结则仍实时聚合，这个人应当在榜上");

            // 已有正常快照后再用错配置 force 重跑：这一段钉的是「错配置不得改动已公示的名次」
            honorProperties.getRanking().setSnapshotTopN(original);
            rankingService.generateSnapshot(RankPeriodType.MONTH, periodKey, false);
            assertTrue(month(RankType.POINTS, periodKey).getFromSnapshot(), "正常配置下应能冻结");

            honorProperties.getRanking().setSnapshotTopN(0);
            assertThrows(BusinessException.class,
                    () -> rankingService.generateSnapshot(RankPeriodType.MONTH, periodKey, true),
                    "force 模式同样要拒绝，而不是照着错配置重算");
            RankingVO kept = month(RankType.POINTS, periodKey);
            assertTrue(kept.getFromSnapshot(), "误配不得让已冻结的周期退回未冻结");
            assertEquals(1, kept.getEntries().size(), "已公示的名次必须原样保留，不能被抹成空榜");
        } finally {
            honorProperties.getRanking().setSnapshotTopN(original);
        }
    }

    @Test
    void snapshot_isIdempotent_secondRunSkipsEveryRankType() {
        LocalDateTime when = LocalDateTime.of(2001, 8, 15, 10, 0);
        Long v = volunteer("幂等验证");
        attend(v, when, 60, SecretaryStatus.CONFIRMED);
        point(v, 50, PointSourceType.ACTIVITY, when);

        rankingService.generateSnapshot(RankPeriodType.MONTH, "2001-08", false);
        SnapshotResultVO again = rankingService.generateSnapshot(RankPeriodType.MONTH, "2001-08", false);

        assertEquals(0, again.getWritten(), "已冻结的周期重跑不写入");
        assertEquals(RankType.AVAILABLE.size(), again.getSkipped().size(),
                "三个板块都应报告为「已存在而跳过」，而不是静默写 0 行");
    }

    /**
     * 并发 {@code force=true} 补跑后，榜单仍然完整。
     *
     * <p><b>这条用例覆盖什么、不覆盖什么，说清楚免得被高估</b>：</p>
     * <ul>
     *   <li><b>覆盖</b>：两个并发补跑都能正常返回（异常经 {@code Future.get()} 抛出，不是只看最终行数——
     *       那样一方整个失败也可能「碰巧」通过），且最终快照没有缺行。</li>
     *   <li><b>不覆盖</b>：它<b>测不出分布式锁被移除</b>。已实测——把锁键换成每次调用都不同（等价于没锁），
     *       本用例连跑 3 次全部通过，因为「先删后写」在 InnoDB 里本就被行锁串行了。
     *       锁的价值在于避免并发补跑之间可能的间隙锁死锁、以及多实例下的重复聚合，
     *       而不是「唯一的串行化手段」。想验证锁本身，得另写针对锁的用例。</li>
     * </ul>
     */
    @Test
    void concurrentForcedSnapshot_keepsSnapshotComplete() throws Exception {
        LocalDateTime when = LocalDateTime.of(2003, 5, 15, 10, 0);
        Long a = volunteer("并发甲");
        Long b = volunteer("并发乙");
        attend(a, when, 60, SecretaryStatus.CONFIRMED);
        attend(a, when, 60, SecretaryStatus.CONFIRMED);
        attend(b, when, 60, SecretaryStatus.CONFIRMED);
        rankingService.generateSnapshot(RankPeriodType.MONTH, "2003-05", false);

        int racers = 2;
        CyclicBarrier startTogether = new CyclicBarrier(racers);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            List<Future<SnapshotResultVO>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit(() -> {
                    startTogether.await(30, TimeUnit.SECONDS);
                    return rankingService.generateSnapshot(RankPeriodType.MONTH, "2003-05", true);
                }));
            }
            for (Future<SnapshotResultVO> f : futures) {
                assertNotNull(f.get(60, TimeUnit.SECONDS), "并发补跑不得抛异常");
            }
        } finally {
            pool.shutdownNow();
        }

        RankingVO vo = month(RankType.ATTENDANCE_COUNT, "2003-05");
        assertTrue(vo.getFromSnapshot());
        assertEquals(2, vo.getEntries().size(), "并发补跑后榜单应完整，不该少人");
        assertEquals(a, vo.getEntries().get(0).getVolunteerId());
        assertEquals(2L, vo.getEntries().get(0).getMetricValue());
    }

    @Test
    void snapshot_rejectsTotalPeriodAndUnfinishedPeriod() {
        assertThrows(BusinessException.class,
                () -> rankingService.generateSnapshot(RankPeriodType.TOTAL, null, false),
                "总榜没有「历史的总榜」，不该生成快照");

        String currentMonth = RankingPeriod.currentMonthKey(LocalDate.now());
        assertThrows(BusinessException.class,
                () -> rankingService.generateSnapshot(RankPeriodType.MONTH, currentMonth, false),
                "当月尚未结束，冻结会得到一份不完整的名次");
    }

    @Test
    void closedPeriodWithoutSnapshot_fallsBackToLiveAggregation() {
        LocalDateTime when = LocalDateTime.of(2001, 9, 15, 10, 0);
        Long v = volunteer("未冻结月份");
        attend(v, when, 60, SecretaryStatus.CONFIRMED);

        RankingVO vo = month(RankType.ATTENDANCE_COUNT, "2001-09");

        assertFalse(vo.getFromSnapshot(),
                "上线前的历史月份还没有快照，应实时聚合并如实标记来源，而不是返回空榜单");
        assertEquals(1, vo.getEntries().size());
        assertEquals(v, vo.getEntries().get(0).getVolunteerId());
    }

    /**
     * 当期恒为实时聚合，<b>即便库里已经有它的快照</b>。
     *
     * <p>直接断言「当月 fromSnapshot=false」是<b>测不出东西的</b>——服务本就拒绝为未结束周期生成快照，
     * 当月永远没有快照可读，那条断言无论 {@code closed()} 怎么写都成立。
     * 故先手工塞一份当月快照与完成标记，再断言查询仍走实时聚合：这样若周期判定被改坏，用例才会红。</p>
     */
    @Test
    void currentMonth_isLiveEvenIfSnapshotRowsExist() {
        String currentMonth = RankingPeriod.currentMonthKey(LocalDate.now());
        Long ghost = volunteer("当月快照幽灵");
        try {
            HonorRankingSnapshot row = new HonorRankingSnapshot();
            row.setPeriodType(RankPeriodType.MONTH);
            row.setPeriodKey(currentMonth);
            row.setRankType(RankType.ATTENDANCE_COUNT);
            row.setVolunteerId(ghost);
            row.setRankNo(1);
            row.setMetricValue(999L);
            snapshotMapper.insert(row);
            HonorRankingSnapshotBatch batch = new HonorRankingSnapshotBatch();
            batch.setPeriodType(RankPeriodType.MONTH);
            batch.setPeriodKey(currentMonth);
            batch.setRankType(RankType.ATTENDANCE_COUNT);
            batch.setRowCount(1);
            batchMapper.insert(batch);

            RankingVO vo = month(RankType.ATTENDANCE_COUNT, currentMonth);

            assertFalse(vo.getFromSnapshot(), "当期榜单恒为实时聚合，不得因库里有快照就去读它");
            assertTrue(vo.getEntries().stream().noneMatch(e -> ghost.equals(e.getVolunteerId())),
                    "当月不该出现只存在于快照里的人");
        } finally {
            clearFrozen(currentMonth);
        }
    }

    // ================= 定时触发 =================

    /**
     * 冷静期：周期刚结束时数据还没结算完（时长要等秘书部确认），不能立刻冻结。
     *
     * <p>用「改配置」而非「挑日期」来验证，否则断言会随运行日期时灵时不灵。</p>
     *
     * <p><b>必须针对「上月」</b>——定时任务只处理上月与上年两个周期。用别的月份会让断言恒真
     * （任务本来就不碰它），测试就永远是绿的。开头显式清掉该周期的冻结状态，
     * 使本用例不依赖与另一个 job 用例的执行顺序。</p>
     */
    @Test
    void snapshotJob_respectsFreezeDelay() {
        String previousMonth = RankingPeriod.previousMonthKey(LocalDate.now());
        clearFrozen(previousMonth);
        Long v = volunteer("冷静期验证");
        attend(v, momentInside(previousMonth), 60, SecretaryStatus.CONFIRMED);

        int original = honorProperties.getRanking().getFreezeDelayDays();
        try {
            honorProperties.getRanking().setFreezeDelayDays(100_000);
            rankingSnapshotJob.generateClosedPeriodSnapshots();
            assertFalse(month(RankType.ATTENDANCE_COUNT, previousMonth).getFromSnapshot(),
                    "冷静期未过，不该冻结——此时榜单仍随数据变动");

            honorProperties.getRanking().setFreezeDelayDays(0);
            rankingSnapshotJob.generateClosedPeriodSnapshots();
            assertTrue(month(RankType.ATTENDANCE_COUNT, previousMonth).getFromSnapshot(),
                    "冷静期已过，定时任务应把上月名次冻结");
        } finally {
            honorProperties.getRanking().setFreezeDelayDays(original);
        }
    }

    /**
     * 开关关闭时定时任务什么都不做。
     *
     * <p><b>同样必须针对「上月」</b>：早先版本用的是「上上个月」，而定时任务根本不处理那个周期，
     * 于是断言恒真——删掉 {@code snapshotEnabled} 判断后用例照样通过（评审已实测），是一条误绿的用例。</p>
     */
    @Test
    void snapshotJob_doesNothingWhenDisabled() {
        String previousMonth = RankingPeriod.previousMonthKey(LocalDate.now());
        clearFrozen(previousMonth);
        Long v = volunteer("开关验证");
        attend(v, momentInside(previousMonth), 60, SecretaryStatus.CONFIRMED);

        boolean originalEnabled = honorProperties.getRanking().isSnapshotEnabled();
        int originalDelay = honorProperties.getRanking().getFreezeDelayDays();
        try {
            // 冷静期设为 0：把「没冻结」的唯一可能原因收敛到开关上，否则冷静期未过也会让断言通过
            honorProperties.getRanking().setFreezeDelayDays(0);
            honorProperties.getRanking().setSnapshotEnabled(false);

            rankingSnapshotJob.generateClosedPeriodSnapshots();

            assertFalse(month(RankType.ATTENDANCE_COUNT, previousMonth).getFromSnapshot(),
                    "开关关闭时定时任务不应写入任何快照");

            // 反向确认：同样的条件下打开开关就会冻结——证明上面的 false 确实是开关造成的
            honorProperties.getRanking().setSnapshotEnabled(true);
            rankingSnapshotJob.generateClosedPeriodSnapshots();
            assertTrue(month(RankType.ATTENDANCE_COUNT, previousMonth).getFromSnapshot(),
                    "开关打开后应立即冻结，否则上面的断言不足以归因于开关");
        } finally {
            honorProperties.getRanking().setSnapshotEnabled(originalEnabled);
            honorProperties.getRanking().setFreezeDelayDays(originalDelay);
        }
    }

    // ================= 入参校验与出参 =================

    /**
     * 微心愿板块（V3 微心愿批放行）：<b>只数「已实现」、按实现时间切周期</b>（《协会待确认清单-v3》⑯ 默认）。
     *
     * <p>四类干扰各放一条，任何一条被算进去都会让名次或数值变：认领中的、已取消但带着实现时间的
     * （脏数据也不该算——口径看状态不看时间列有没有值）、恰好落在下月第一秒的（左闭右开）、上月最后一秒的。
     * 用 1998 年：别的用例占的是 2001 / 2004 年，排行是全库聚合，年份错开才能写绝对断言。</p>
     */
    @Test
    void wishRanking_countsRealizedClaimsOnly_byRealizeTime() {
        LocalDateTime inMonth = LocalDateTime.of(1998, 3, 15, 10, 0);
        Long top = volunteer("圆梦第一");
        Long second = volunteer("圆梦第二");
        wishClaim(top, WishFlow.CLAIM_REALIZED, inMonth);
        wishClaim(top, WishFlow.CLAIM_REALIZED, inMonth.plusDays(3));
        wishClaim(second, WishFlow.CLAIM_REALIZED, inMonth);
        wishClaim(second, WishFlow.CLAIM_ACTIVE, null);
        wishClaim(second, WishFlow.CLAIM_CANCELLED, inMonth);
        wishClaim(second, WishFlow.CLAIM_REALIZED, LocalDateTime.of(1998, 4, 1, 0, 0));
        wishClaim(second, WishFlow.CLAIM_REALIZED, LocalDateTime.of(1998, 2, 28, 23, 59, 59));

        RankingVO vo = month(RankType.WISH, "1998-03");
        List<RankingEntryVO> entries = vo.getEntries();
        assertEquals(2, entries.size());
        assertEquals(top, entries.get(0).getVolunteerId());
        assertEquals(2L, entries.get(0).getMetricValue());
        assertEquals(second, entries.get(1).getVolunteerId());
        assertEquals(1L, entries.get(1).getMetricValue(), "认领中 / 已取消 / 月外的都不算");
        assertEquals("微心愿排行", vo.getRankTypeLabel());
        assertEquals("个", vo.getUnit());

        SnapshotResultVO frozen = rankingService.generateSnapshot(RankPeriodType.MONTH, "1998-03", false);
        assertTrue(frozen.getFrozen().contains(RankType.labelOf(RankType.WISH)),
                "微心愿板块要跟另外三个一起冻结（frozen 装的是板块名不是码——拿码去 contains 恒为 false）：" + frozen.getFrozen());
        wishClaim(second, WishFlow.CLAIM_REALIZED, inMonth);
        wishClaim(second, WishFlow.CLAIM_REALIZED, inMonth);
        RankingVO after = month(RankType.WISH, "1998-03");
        assertTrue(after.getFromSnapshot());
        assertEquals(top, after.getEntries().get(0).getVolunteerId(), "冻结之后补进来的实现记录不改往期名次");
    }

    @Test
    void invalidArguments_areRejected() {
        assertThrows(BusinessException.class,
                () -> rankingService.ranking(99, RankPeriodType.TOTAL, null, 10), "未知榜单类型");
        assertThrows(BusinessException.class,
                () -> rankingService.ranking(RankType.POINTS, 99, null, 10), "未知周期类型");
        assertThrows(BusinessException.class,
                () -> rankingService.ranking(RankType.POINTS, RankPeriodType.MONTH, "2001-13", 10),
                "月份 13 非法");
        assertThrows(BusinessException.class,
                () -> rankingService.ranking(RankType.POINTS, RankPeriodType.MONTH, "2001/06", 10),
                "分隔符错误");
        assertThrows(BusinessException.class,
                () -> rankingService.ranking(RankType.POINTS, RankPeriodType.YEAR, "26", 10),
                "年份需 4 位");
    }

    @Test
    void limit_isNormalizedAndCapped() {
        LocalDateTime when = LocalDateTime.of(2001, 11, 15, 10, 0);
        Long first = volunteer("限流第一");
        Long second = volunteer("限流第二");
        point(first, 20, PointSourceType.ACTIVITY, when);
        point(second, 10, PointSourceType.ACTIVITY, when);

        assertEquals(2, rankingService.ranking(RankType.POINTS, RankPeriodType.MONTH, "2001-11", null)
                .getEntries().size(), "limit 为空取默认，够用");
        List<RankingEntryVO> onlyTop = rankingService
                .ranking(RankType.POINTS, RankPeriodType.MONTH, "2001-11", 1).getEntries();
        assertEquals(1, onlyTop.size());
        assertEquals(first, onlyTop.get(0).getVolunteerId());
        assertEquals(2, rankingService.ranking(RankType.POINTS, RankPeriodType.MONTH, "2001-11", -5)
                .getEntries().size(), "非法 limit 退回默认");
    }

    /**
     * 超大 {@code limit} 被截到上限。
     *
     * <p>单独一条用例，因为要真的造出<b>多于上限</b>的候选人——数据不够时
     * 「结果条数 ≤ 上限」是恒真的，断言不了任何东西。</p>
     */
    @Test
    void limit_isCappedWhenCandidatesExceedMax() {
        LocalDateTime when = LocalDateTime.of(2004, 1, 15, 10, 0);
        int candidates = RankingService.MAX_LIMIT + 5;
        for (int i = 0; i < candidates; i++) {
            point(volunteer("上限验证" + i), 10 + i, PointSourceType.ACTIVITY, when);
        }

        assertEquals(RankingService.MAX_LIMIT,
                rankingService.ranking(RankType.POINTS, RankPeriodType.MONTH, "2004-01", 100_000)
                        .getEntries().size(), "超大 limit 应被截到上限");
        assertEquals(RankingService.DEFAULT_LIMIT,
                rankingService.ranking(RankType.POINTS, RankPeriodType.MONTH, "2004-01", null)
                        .getEntries().size(), "不传 limit 取默认页大小");
    }

    @Test
    void entries_carryFullNameAndPlaceholderForUnnamed() {
        LocalDateTime when = LocalDateTime.of(2001, 10, 15, 10, 0);
        Long named = volunteer("张三丰");
        Long unnamed = guest();
        point(named, 50, PointSourceType.ACTIVITY, when);
        point(unnamed, 40, PointSourceType.ACTIVITY, when);

        List<RankingEntryVO> entries = month(RankType.POINTS, "2001-10").getEntries();

        assertEquals("张三丰", entries.get(0).getVolunteerName(), "显示完整姓名，不做姓氏脱敏");
        assertEquals("已注销志愿者", entries.get(1).getVolunteerName(),
                "查不到姓名时保留行、只换占位——历史贡献不该因注销而从榜上消失");
    }

    @Test
    void rankingVO_carriesLabelsAndUnit() {
        RankingVO vo = rankingService.ranking(RankType.SERVICE_MINUTES, RankPeriodType.YEAR, "2001", 10);
        assertEquals("活动时长排行", vo.getRankTypeLabel());
        assertEquals("分钟", vo.getUnit());
        assertEquals("年榜", vo.getPeriodTypeLabel());
        assertEquals("2001", vo.getPeriodKey());
        assertNotNull(vo.getEntries());
    }

    // ---------- helpers ----------

    private RankingVO month(int rankType, String periodKey) {
        return rankingService.ranking(rankType, RankPeriodType.MONTH, periodKey, 50);
    }

    /** 该月内的一个时刻（月初次日 10:00），用于把考勤/流水放进指定月份。 */
    private LocalDateTime momentInside(String monthKey) {
        return RankingPeriod.of(RankPeriodType.MONTH, monthKey).from().plusDays(1).withHour(10);
    }

    /**
     * 清掉某月三个板块的冻结状态（快照行 + 完成标记，均物理删除）。
     *
     * <p>两个 job 用例都要操作「上月」这同一个周期，靠开头清一次使它们不依赖执行顺序。</p>
     */
    private void clearFrozen(String monthKey) {
        for (Integer type : RankType.AVAILABLE) {
            snapshotMapper.deletePeriod(RankPeriodType.MONTH, monthKey, type);
            batchMapper.deleteBatch(RankPeriodType.MONTH, monthKey, type);
        }
    }

    private Long volunteer(String realName) {
        Volunteer v = new Volunteer();
        v.setOpenid("rank:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setStatus(0);
        v.setRealName(realName);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    /** 未实名的游客——用于验证「查不到姓名」时的占位显示。 */
    private Long guest() {
        Volunteer v = new Volunteer();
        v.setOpenid("rank:guest:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setStatus(0);
        volunteerMapper.insert(v);
        return v.getId();
    }

    /** 直接落一条认领记录（心愿 id 取一个不会撞 uk_active_wish 的号——排行只看认领表）。 */
    private void wishClaim(Long volunteerId, int status, LocalDateTime realizeTime) {
        DonateWishClaim c = new DonateWishClaim();
        c.setWishId(700_000_000L + SEQ.incrementAndGet());
        c.setVolunteerId(volunteerId);
        c.setStatus(status);
        c.setClaimTime(realizeTime == null ? LocalDateTime.now() : realizeTime.minusDays(5));
        c.setRealizeTime(realizeTime);
        wishClaimMapper.insert(c);
    }

    private Long activity(int status) {
        Activity a = new Activity();
        a.setTitle("排行活动_" + System.nanoTime() + "_" + SEQ.incrementAndGet());
        a.setStartTime(LocalDateTime.now());
        a.setEndTime(LocalDateTime.now().plusHours(2));
        a.setStatus(status);
        activityMapper.insert(a);
        return a.getId();
    }

    /**
     * 每次新建一个活动承载考勤。
     *
     * <p>V30 后唯一键是 {@code uk_activity_volunteer_slot}，同人同活动的<b>不同场次</b>已允许多行；
     * 但「活动次数」按 {@code COUNT(DISTINCT activity_id)} 计，故要造出 N 次活动仍须建 N 个活动，
     * 只加场次不会让次数上去。</p>
     */
    private void attend(Long volunteerId, LocalDateTime checkIn, int minutes, int secretaryStatus) {
        attendOn(activity(ActivityStatus.PUBLISHED), volunteerId, checkIn, minutes, secretaryStatus);
    }

    private void attendOn(Long activityId, Long volunteerId, LocalDateTime checkIn,
                          int minutes, int secretaryStatus) {
        ActivityAttendance att = new ActivityAttendance();
        att.setActivityId(activityId);
        att.setSlotId(insertSlot(activityId));   // V30：slot_id NOT NULL
        att.setVolunteerId(volunteerId);
        att.setCheckInTime(checkIn);
        att.setServiceMinutes(minutes);
        att.setSecretaryStatus(secretaryStatus);
        attendanceMapper.insert(att);
    }

    private Long insertSlot(Long activityId) {
        ActivitySlot slot = new ActivitySlot();
        slot.setActivityId(activityId);
        slot.setProjectName("岗位_" + System.nanoTime() + "_" + SEQ.incrementAndGet());
        slot.setStartTime(LocalDateTime.now().minusDays(1));
        slot.setEndTime(LocalDateTime.now().minusDays(1).plusHours(2));
        slot.setNeedCount(10);
        slotMapper.insert(slot);
        return slot.getId();
    }

    /**
     * 直接落流水而不经 {@code PointService}：本用例要把流水放到指定的历史月份，
     * 而 {@code create_time} 由自动填充置为当前时间。{@code strictInsertFill} 只在字段为 null 时填，
     * 显式赋值会被保留。
     */
    private void point(Long volunteerId, int amount, int sourceType, LocalDateTime createTime) {
        PointRecord r = new PointRecord();
        r.setVolunteerId(volunteerId);
        r.setChangeAmount(amount);
        r.setSourceType(sourceType);
        r.setSourceId(900_000_000L + SEQ.incrementAndGet());
        r.setRemark("排行用例");
        r.setOperatorType(PointSourceType.OPERATOR_SYSTEM);
        r.setCreateTime(createTime);
        pointRecordMapper.insert(r);
    }
}
