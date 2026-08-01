package com.hengde.activity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.dao.PointRecordMapper;
import com.hengde.activity.dto.PointAdjustDTO;
import com.hengde.activity.entity.PointRecord;
import com.hengde.activity.service.PointService;
import com.hengde.activity.vo.PointRecordVO;
import com.hengde.activity.vo.PointSummaryVO;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 积分账本验证（V2 第 1 批）。
 *
 * <p><b>幂等与并发是本套用例的重点</b>——积分等同权益，重复入账或余额错乱都是不可接受的资损类缺陷，
 * 这些断言不要因为「看起来在测框架」而删除。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class PointServiceTest {

    @Autowired
    private PointService pointService;
    @Autowired
    private PointRecordMapper pointRecordMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 显式钉死 REPEATABLE READ 的事务模板。
     *
     * <p>这几个用例依赖的是「事务内快照早于对方提交，故预查看不到那一行」——这个前提<b>只在 RR 下成立</b>。
     * 若哪天数据库默认隔离级别被改成 READ COMMITTED，预查就能读到最新数据、直接从幂等分支返回，
     * DuplicateKey 那条路径根本不执行，而最终余额断言仍然全绿——测试会悄悄退化成没测。
     * 显式指定隔离级别，让前提由用例自己保证，不依赖环境默认值。</p>
     */
    private TransactionTemplate repeatableRead;

    @BeforeEach
    void initRepeatableReadTemplate() {
        repeatableRead = new TransactionTemplate(transactionTemplate.getTransactionManager());
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    // ---------- 基本入账/出账 ----------

    @Test
    void record_accumulatesBalance() {
        Long vid = insertVolunteer("积分甲");
        assertEquals(0, pointService.balanceOf(vid), "无流水时余额应为 0");

        assertEquals(10, pointService.record(vid, 10, PointSourceType.ACTIVITY, 1001L,
                "活动A", PointSourceType.OPERATOR_SYSTEM, null));
        assertEquals(25, pointService.record(vid, 15, PointSourceType.ACTIVITY, 1002L,
                "活动B", PointSourceType.OPERATOR_SYSTEM, null));
        assertEquals(25, pointService.balanceOf(vid));
    }

    @Test
    void record_negativeAmount_deducts() {
        Long vid = insertVolunteer("积分乙");
        pointService.record(vid, 30, PointSourceType.ACTIVITY, 2001L, "活动", PointSourceType.OPERATOR_SYSTEM, null);
        assertEquals(20, pointService.record(vid, -10, PointSourceType.CORRECTION, 2002L,
                "修正", PointSourceType.OPERATOR_ADMIN, 9L));
        assertEquals(20, pointService.balanceOf(vid));
    }

    @Test
    void record_zeroAmount_rejected() {
        Long vid = insertVolunteer("积分丙");
        assertThrows(BusinessException.class, () -> pointService.record(
                vid, 0, PointSourceType.ACTIVITY, 3001L, "零分", PointSourceType.OPERATOR_SYSTEM, null));
    }

    // ---------- 幂等（核心） ----------

    /** 同一来源单据重复入账必须只记一次——否则志愿者积分会随重放翻倍 */
    @Test
    void record_sameSource_isIdempotent() {
        Long vid = insertVolunteer("积分丁");
        pointService.record(vid, 20, PointSourceType.ACTIVITY, 4001L, "活动", PointSourceType.OPERATOR_SYSTEM, null);
        pointService.record(vid, 20, PointSourceType.ACTIVITY, 4001L, "活动", PointSourceType.OPERATOR_SYSTEM, null);
        pointService.record(vid, 20, PointSourceType.ACTIVITY, 4001L, "活动", PointSourceType.OPERATOR_SYSTEM, null);

        assertEquals(20, pointService.balanceOf(vid), "重复入账应被幂等拦截");
        assertEquals(1, pointService.pageRecords(vid, page(), null).getRecords().size());
    }

    /** 同 sourceId 但不同 sourceType 是不同来源，各记一笔（唯一键是二元组） */
    @Test
    void record_sameIdDifferentType_bothRecorded() {
        Long vid = insertVolunteer("积分戊");
        pointService.record(vid, 10, PointSourceType.ACTIVITY, 5001L, "活动", PointSourceType.OPERATOR_SYSTEM, null);
        pointService.record(vid, 5, PointSourceType.CORRECTION, 5001L, "修正", PointSourceType.OPERATOR_ADMIN, 9L);
        assertEquals(15, pointService.balanceOf(vid));
    }

    /**
     * sourceId 与 requestId 皆为 null 时不做幂等判定，每次都入账。
     *
     * <p>这正是 {@code adjust} 必须强制传 requestId 的原因——手工调整走的就是 sourceId=null 这条路，
     * 没有幂等键就是这个「每次都记」的行为，重放会真的重复加减分。</p>
     */
    @Test
    void record_nullSourceId_notDeduplicated() {
        Long vid = insertVolunteer("积分己");
        pointService.record(vid, 3, PointSourceType.MANUAL, null, "调整1", PointSourceType.OPERATOR_ADMIN, 9L);
        pointService.record(vid, 3, PointSourceType.MANUAL, null, "调整2", PointSourceType.OPERATOR_ADMIN, 9L);
        assertEquals(6, pointService.balanceOf(vid), "手工调整不应被去重");
    }

    // ---------- 并发 ----------

    /** 并发入账后余额必须等于各笔之和，不能因「读余额→累加→插入」非原子而丢更新 */
    @Test
    void record_concurrent_balanceStaysConsistent() throws Exception {
        Long vid = insertVolunteer("积分庚");
        int threads = 8;
        AtomicInteger seq = new AtomicInteger();
        List<Integer> results = runConcurrently(threads, () -> pointService.record(
                vid, 5, PointSourceType.ACTIVITY, 6000L + seq.getAndIncrement(),
                "并发", PointSourceType.OPERATOR_SYSTEM, null));

        assertEquals(threads, results.size(), "全部入账应成功（任一抛异常都会让本用例失败）");
        assertEquals(threads * 5, pointService.balanceOf(vid), "并发下余额必须等于各笔之和");
        assertEquals(threads, pointService.pageRecords(vid, page(), null).getRecords().size());
    }

    /**
     * 并发重放同一来源：只应入账一次，且<b>每个调用都要成功返回正确余额</b>。
     *
     * <p>本用例原先把异常吞掉只看最终余额——那样即使 6 次里 5 次抛异常，余额仍是 7，测试照样绿，
     * 等于没测幂等路径的「不抛异常」这一半语义。现在收集 Future 逐个 get，任何一次失败都会让用例红。</p>
     */
    @Test
    void record_concurrentSameSource_onlyOnce() throws Exception {
        Long vid = insertVolunteer("积分辛");
        List<Integer> results = runConcurrently(6, () -> pointService.record(
                vid, 7, PointSourceType.ACTIVITY, 7001L, "重放", PointSourceType.OPERATOR_SYSTEM, null));

        assertEquals(6, results.size(), "全部调用都应成功返回，幂等路径不得抛异常");
        assertTrue(results.stream().allMatch(b -> b == 7), "每次返回的余额都应是 7，实际=" + results);
        assertEquals(7, pointService.balanceOf(vid), "并发重放同一来源只应入账一次");
        assertEquals(1, pointService.pageRecords(vid, page(), null).getRecords().size(), "只应留下一条流水");
    }

    /**
     * <b>真正命中「INSERT → DuplicateKeyException → FOR SHARE 当前读复核」分支</b>的重放用例。
     *
     * <p>顺序必须是：事务 A 先做一次读建立 RR 快照（此时查无此键）→ <b>另一个连接</b>插入并提交
     * → 事务 A 再调 {@code record}。这样 A 的预查仍看不到那行（快照早于 B 提交），
     * 才会走到 INSERT 撞唯一键、再用当前读取回冲突行复核。</p>
     *
     * <p>若像早前那样在事务开始<b>之前</b>就把首条流水提交掉，事务内第一次预查就命中并直接返回，
     * 整条 DuplicateKey 分支根本不会执行——那正是本轮修复的核心逻辑，却一直没有自动化保护。</p>
     */
    @Test
    void record_concurrentReplayInsideTransaction_hitsDuplicateKeyBranch() {
        Long vid = insertVolunteer("事务甲");
        long sourceId = 7101L;

        repeatableRead.executeWithoutResult(status -> {
            // ① 建立 RR 快照：此刻 point_record 里没有这一笔
            assertEquals(0, pointService.balanceOf(vid));
            // ② 另一个连接抢先插入并提交
            commitInOtherThread(() -> pointService.record(vid, 9, PointSourceType.ACTIVITY, sourceId,
                    "并发首次", PointSourceType.OPERATOR_SYSTEM, null));
            // 前提自检：对方已提交，但本事务的快照仍看不到——不成立就说明没跑在 RR 下，
            // 后面的 DuplicateKey 分支根本不会触发，用例会退化成「没测」
            assertNull(pointRecordMapper.selectBySource(PointSourceType.ACTIVITY, sourceId),
                    "RR 快照应仍看不到对方刚提交的行，否则本用例覆盖不到目标分支");
            // ③ 本事务再入账：预查受快照限制看不到 ②，必然走到 INSERT → 唯一键冲突 → FOR SHARE 当前读复核
            //    载荷一致，应按重放静默跳过、不抛异常（三条生产链路都依赖这个行为）
            pointService.record(vid, 9, PointSourceType.ACTIVITY, sourceId,
                    "并发重放", PointSourceType.OPERATOR_SYSTEM, null);
        });

        assertEquals(9, pointService.balanceOf(vid), "只应入账一次");
        assertEquals(1, pointService.pageRecords(vid, page(), null).getRecords().size());
    }

    /**
     * <b>多个事务同时撞同一个键</b>时，服务层应各自正常返回、只入账一次。
     *
     * <p>背景：INSERT 报重复键时 InnoDB 会给冲突行加<b>共享锁</b>；复核若要排他锁，多个 loser 便会
     * 互等成环、被判 {@code ERROR 1213 Deadlock}。生产代码为此使用 {@code FOR SHARE}。</p>
     *
     * <p><b>本用例只保证服务控制流正确，不保证必然复现锁冲突</b>——屏障只能卡在「三个快照已建立、
     * 即将调用 {@code record()}」处，无法让三者精确地同时停在「即将发起复核读」那一刻；
     * 调度偏差下第一个 loser 可能已复核完毕，后面的才动。
     * <b>「复核不得要求排他锁」这条结论由 {@link PointRecordLockOrderTest} 确定性地保证</b>，
     * 那里把锁状态做成先决条件，把 SQL 换成 {@code FOR UPDATE} 必然死锁。两个用例分工不同，都别删。</p>
     */
    @Test
    void record_multipleConcurrentLosers_noDeadlock() throws Exception {
        Long vid = insertVolunteer("死锁甲");
        long sourceId = 7301L;
        int losers = 3;

        CountDownLatch snapshotsReady = new CountDownLatch(losers);
        CountDownLatch winnerCommitted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(losers);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < losers; i++) {
                futures.add(pool.submit(() -> repeatableRead.execute(status -> {
                    pointService.balanceOf(vid);        // ① 建立 RR 快照：此刻查无此键
                    snapshotsReady.countDown();
                    awaitQuietly(winnerCommitted);      // ② 等赢家提交
                    // ③ 全部 loser 同时入账 → 同时撞唯一键 → 同时复核
                    return pointService.record(vid, 4, PointSourceType.ACTIVITY, sourceId,
                            "并发重放", PointSourceType.OPERATOR_SYSTEM, null);
                })));
            }
            assertTrue(snapshotsReady.await(30, TimeUnit.SECONDS), "快照建立超时");
            commitInOtherThread(() -> pointService.record(vid, 4, PointSourceType.ACTIVITY, sourceId,
                    "赢家", PointSourceType.OPERATOR_SYSTEM, null));
            winnerCommitted.countDown();

            for (Future<Integer> f : futures) {
                f.get(60, TimeUnit.SECONDS);   // 任一 loser 死锁都会在此抛出
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(4, pointService.balanceOf(vid), "无论多少 loser，只应入账一次");
        assertEquals(1, pointService.pageRecords(vid, page(), null).getRecords().size());
    }

    /**
     * 同一分支下的<b>载荷冲突</b>必须抛出——若当前读复核缺失或写错，这里会静默放行、悄悄丢掉一笔账。
     */
    @Test
    void record_concurrentConflictInsideTransaction_throws() {
        Long a = insertVolunteer("事务乙");
        Long b = insertVolunteer("事务丙");
        long sourceId = 7201L;

        assertThrows(BusinessException.class, () -> repeatableRead.executeWithoutResult(status -> {
            assertEquals(0, pointService.balanceOf(b));                       // 建立快照
            commitInOtherThread(() -> pointService.record(a, 9, PointSourceType.ACTIVITY, sourceId,
                    "A 的流水", PointSourceType.OPERATOR_SYSTEM, null));      // 另一连接抢先提交
            assertNull(pointRecordMapper.selectBySource(PointSourceType.ACTIVITY, sourceId),
                    "RR 快照应仍看不到 A 刚提交的行，否则覆盖不到 DuplicateKey 分支");
            pointService.record(b, 9, PointSourceType.ACTIVITY, sourceId,
                    "B 撞了同一个键", PointSourceType.OPERATOR_SYSTEM, null); // 撞键且载荷不同 → 必须报冲突
        }));

        assertEquals(9, pointService.balanceOf(a));
        assertEquals(0, pointService.balanceOf(b), "B 的那笔不应被当成重放吞掉");
    }

    // ---------- 总览与明细 ----------

    /**
     * 总览按<b>来源</b>分「获得 / 已使用」，不按正负分。
     *
     * <p>本用例原先断言 earned=70 / spent=30（把 -30 的修正当成了消费），
     * 那与需求「总积分、已使用积分」的语义不符——志愿者并没有花掉 30 分，
     * 那 30 分是当初发多了被改回来的。现口径下修正冲减「累计获得」。</p>
     */
    @Test
    void summary_splitsEarnedAndSpent() {
        Long vid = insertVolunteer("积分壬");
        pointService.record(vid, 50, PointSourceType.ACTIVITY, 8001L, "活动", PointSourceType.OPERATOR_SYSTEM, null);
        pointService.record(vid, 20, PointSourceType.ACTIVITY, 8002L, "活动", PointSourceType.OPERATOR_SYSTEM, null);
        pointService.record(vid, -30, PointSourceType.CORRECTION, 8003L, "修正", PointSourceType.OPERATOR_ADMIN, 9L);

        PointSummaryVO s = pointService.summary(vid);
        assertEquals(40, s.getTotalEarned(), "70 发放 − 30 修正 = 40，修正冲减获得而非计入支出");
        assertEquals(0, s.getTotalSpent(), "没有兑换消费，已使用应为 0");
        assertEquals(40, s.getBalance());
    }

    @Test
    void summary_noRecords_returnsZeros() {
        Long vid = insertVolunteer("积分癸");
        PointSummaryVO s = pointService.summary(vid);
        assertEquals(0, s.getTotalEarned());
        assertEquals(0, s.getTotalSpent());
        assertEquals(0, s.getBalance());
    }

    @Test
    void pageRecords_filtersBySourceTypeAndCarriesLabel() {
        Long vid = insertVolunteer("积分子");
        pointService.record(vid, 10, PointSourceType.ACTIVITY, 9001L, "活动", PointSourceType.OPERATOR_SYSTEM, null);
        pointService.record(vid, 4, PointSourceType.MANUAL, null, "手工加分", PointSourceType.OPERATOR_ADMIN, 9L);

        PageResult<PointRecordVO> manual = pointService.pageRecords(vid, page(), PointSourceType.MANUAL);
        assertEquals(1, manual.getRecords().size());
        PointRecordVO vo = manual.getRecords().get(0);
        assertEquals("管理员调整", vo.getSourceTypeName(), "出参应带中文来源名");
        assertEquals(4, vo.getChangeAmount());
        assertEquals("手工加分", vo.getRemark());

        assertEquals(2, pointService.pageRecords(vid, page(), null).getRecords().size(), "不筛选应返回全部");
    }

    @Test
    void batchBalance_returnsPerVolunteer() {
        Long a = insertVolunteer("积分丑");
        Long b = insertVolunteer("积分寅");
        pointService.record(a, 12, PointSourceType.ACTIVITY, 9101L, "活动", PointSourceType.OPERATOR_SYSTEM, null);

        Map<Long, Integer> map = pointService.batchBalance(List.of(a, b));
        assertEquals(12, map.get(a));
        assertTrue(map.get(b) == null, "无流水者不出现在结果中，由调用方按 0 处理");
    }

    // ---------- 手工调整 ----------

    @Test
    void adjust_requiresOperatorReasonAndRequestId() {
        Long vid = insertVolunteer("积分卯");
        PointAdjustDTO dto = adjustDto(vid, 5, "补偿");

        assertThrows(BusinessException.class, () -> pointService.adjust(dto, null), "操作人为空应拒绝");

        dto.setReason("  ");
        assertThrows(BusinessException.class, () -> pointService.adjust(dto, 9L), "原因为空应拒绝");

        dto.setReason("补偿");
        dto.setRequestId(" ");
        assertThrows(BusinessException.class, () -> pointService.adjust(dto, 9L), "缺幂等键应拒绝");
    }

    /** 扣分不得把余额扣成负数——手工扣分是主观决策，余额不足应让管理员重新判断 */
    @Test
    void adjust_cannotDeductBelowZero() {
        Long vid = insertVolunteer("积分辰");
        pointService.record(vid, 10, PointSourceType.ACTIVITY, 9201L, "活动", PointSourceType.OPERATOR_SYSTEM, null);

        PointAdjustDTO tooMuch = adjustDto(vid, -20, "扣太多");
        assertThrows(BusinessException.class, () -> pointService.adjust(tooMuch, 9L));
        assertEquals(10, pointService.balanceOf(vid), "被拒的调整不应改变余额");

        assertEquals(0, pointService.adjust(adjustDto(vid, -10, "扣至零"), 9L), "恰好扣至 0 应放行");
    }

    /**
     * 同一 requestId 重放只入账一次。手工调整的 sourceId 恒为 null，MySQL 唯一索引视多个 NULL 互不相同，
     * uk_source 挡不住它——双击/超时重试/网关重放会实打实地重复加减分，故必须靠 request_id 兜住。
     */
    @Test
    void adjust_sameRequestId_isIdempotent() {
        Long vid = insertVolunteer("积分巳");
        PointAdjustDTO dto = adjustDto(vid, 25, "表彰加分");

        assertEquals(25, pointService.adjust(dto, 9L));
        assertEquals(25, pointService.adjust(dto, 9L), "重放不应再次加分");
        assertEquals(25, pointService.adjust(dto, 9L));

        assertEquals(25, pointService.balanceOf(vid));
        assertEquals(1, pointService.pageRecords(vid, page(), PointSourceType.MANUAL).getRecords().size(),
                "重放只应留下一条流水");
    }

    /** 不同 requestId 是两次独立调整，都要生效——幂等不能误伤「管理员连着调两次」 */
    @Test
    void adjust_differentRequestId_bothApply() {
        Long vid = insertVolunteer("积分午");
        assertEquals(5, pointService.adjust(adjustDto(vid, 5, "第一次"), 9L));
        assertEquals(13, pointService.adjust(adjustDto(vid, 8, "第二次"), 9L));
        assertEquals(2, pointService.pageRecords(vid, page(), PointSourceType.MANUAL).getRecords().size());
    }

    /** 并发重放同一次调整（双击/重试打到多个实例）：只应入账一次，且每次都要成功返回正确余额 */
    @Test
    void adjust_concurrentReplay_onlyOnce() throws Exception {
        Long vid = insertVolunteer("积分未");
        PointAdjustDTO dto = adjustDto(vid, 11, "并发重放");
        List<Integer> results = runConcurrently(6, () -> pointService.adjust(dto, 9L));

        assertEquals(6, results.size(), "全部调用都应成功返回，幂等路径不得抛异常");
        assertTrue(results.stream().allMatch(b -> b == 11), "每次返回的余额都应是 11，实际=" + results);
        assertEquals(11, pointService.balanceOf(vid), "并发重放同一 requestId 只应入账一次");
        assertEquals(1, pointService.pageRecords(vid, page(), PointSourceType.MANUAL).getRecords().size());
    }

    // ---------- 幂等键载荷复核（键相同但不是同一笔） ----------

    /**
     * 同一 requestId 被误用于另一个人/另一个金额时，必须<b>报错</b>而不是返回成功。
     *
     * <p>只判「键存在」的话，第二次调用会拿到成功响应和目标志愿者的当前余额，
     * 而那笔分根本没入账——调用方与前端都无从察觉，账就这么少了一笔。
     */
    @Test
    void record_sameRequestIdDifferentPayload_rejected() {
        Long a = insertVolunteer("载荷甲");
        Long b = insertVolunteer("载荷乙");
        String reused = UUID.randomUUID().toString();

        pointService.record(a, 10, PointSourceType.MANUAL, null, reused, "给甲加分",
                PointSourceType.OPERATOR_ADMIN, 9L);

        BusinessException ex = assertThrows(BusinessException.class, () -> pointService.record(
                b, 20, PointSourceType.MANUAL, null, reused, "误用同一键给乙加分",
                PointSourceType.OPERATOR_ADMIN, 9L));
        assertTrue(ex.getMessage().contains("冲突"), "应明确报幂等冲突，实际=" + ex.getMessage());
        assertEquals(0, pointService.balanceOf(b), "乙不应有任何流水");
        assertEquals(10, pointService.balanceOf(a), "甲的流水不受影响");
    }

    /** 同一 requestId、同一个人但金额不同，同样必须报冲突 */
    @Test
    void record_sameRequestIdDifferentAmount_rejected() {
        Long vid = insertVolunteer("载荷丙");
        String reused = UUID.randomUUID().toString();
        pointService.record(vid, 10, PointSourceType.MANUAL, null, reused, "第一次",
                PointSourceType.OPERATOR_ADMIN, 9L);

        assertThrows(BusinessException.class, () -> pointService.record(
                vid, 99, PointSourceType.MANUAL, null, reused, "金额变了",
                PointSourceType.OPERATOR_ADMIN, 9L));
        assertEquals(10, pointService.balanceOf(vid));
    }

    /** 来源单据键撞车（如新来源复用了旧 sourceType）同样应报冲突，而不是静默丢分 */
    @Test
    void record_sameSourceDifferentPayload_rejected() {
        Long a = insertVolunteer("载荷丁");
        Long b = insertVolunteer("载荷戊");
        pointService.record(a, 30, PointSourceType.ACTIVITY, 9501L, "活动", PointSourceType.OPERATOR_SYSTEM, null);

        assertThrows(BusinessException.class, () -> pointService.record(
                b, 30, PointSourceType.ACTIVITY, 9501L, "撞键", PointSourceType.OPERATOR_SYSTEM, null));
        assertEquals(0, pointService.balanceOf(b), "撞键的那一笔不应被当成已入账而丢掉");
    }

    /**
     * requestId 路径还要比对<b>理由与操作人</b>：同人同额但换了理由，是另一次调整。
     *
     * <p>若放行，账本会留着第一次的理由和操作人——而这两项恰恰是手工调分唯一的追溯依据，
     * 审计链就此对不上。</p>
     */
    @Test
    void record_sameRequestIdDifferentReason_rejected() {
        Long vid = insertVolunteer("载荷辛");
        String reused = UUID.randomUUID().toString();
        pointService.record(vid, 10, PointSourceType.MANUAL, null, reused, "迟到扣分",
                PointSourceType.OPERATOR_ADMIN, 9L);

        assertThrows(BusinessException.class, () -> pointService.record(
                vid, 10, PointSourceType.MANUAL, null, reused, "其实是表彰加分",
                PointSourceType.OPERATOR_ADMIN, 9L), "换了理由应视为另一笔");
    }

    /** 同人同额同理由但换了操作人，同样是另一次调整 */
    @Test
    void record_sameRequestIdDifferentOperator_rejected() {
        Long vid = insertVolunteer("载荷壬");
        String reused = UUID.randomUUID().toString();
        pointService.record(vid, 10, PointSourceType.MANUAL, null, reused, "补偿",
                PointSourceType.OPERATOR_ADMIN, 9L);

        assertThrows(BusinessException.class, () -> pointService.record(
                vid, 10, PointSourceType.MANUAL, null, reused, "补偿",
                PointSourceType.OPERATOR_ADMIN, 77L), "换了操作人应视为另一笔");
    }

    /**
     * 来源单据路径<b>不</b>比对理由与操作人：活动可能改名、也可能换个管理员重试同一张单据，
     * 把展示文案纳入比对会把正常重放误判成冲突。
     */
    @Test
    void record_sameSourceDifferentRemarkAndOperator_stillReplay() {
        Long vid = insertVolunteer("载荷癸");
        pointService.record(vid, 12, PointSourceType.ACTIVITY, 9550L, "参加旧活动名",
                PointSourceType.OPERATOR_ADMIN, 9L);

        assertEquals(12, pointService.record(vid, 12, PointSourceType.ACTIVITY, 9550L,
                "参加新活动名", PointSourceType.OPERATOR_ADMIN, 77L), "同单据同金额仍应按重放跳过");
        assertEquals(1, pointService.pageRecords(vid, page(), PointSourceType.ACTIVITY).getRecords().size());
    }

    /** 超长 remark 必须安全截断，而不是让 DB 抛 data too long 把调用方事务带崩 */
    @Test
    void record_overlongRemark_truncatedNotFailed() {
        Long vid = insertVolunteer("截断甲");
        String longReason = "理由".repeat(400);   // 800 字符，远超 remark 列的 512

        assertEquals(5, pointService.record(vid, 5, PointSourceType.CORRECTION, 9560L, longReason,
                PointSourceType.OPERATOR_ADMIN, 9L));
        String stored = pointService.pageRecords(vid, page(), null).getRecords().get(0).getRemark();
        assertEquals(512, stored.codePointCount(0, stored.length()), "应截断到列宽（按字符数）");
        assertTrue(stored.endsWith("…"), "末位应有省略号示意被截断");
    }

    /**
     * 截断必须按 code point，不能把 emoji 的代理对切成两半。
     *
     * <p>Java 的 {@code String.length()} 数的是 UTF-16 码元，emoji 占 2 个；从中间切会留下半个字符，
     * 存进 utf8mb4 后展示成乱码、并破坏该行的搜索匹配。而 MySQL 的 VARCHAR 数的是字符，
     * 两边计数单位必须对齐。</p>
     */
    @Test
    void record_overlongRemarkWithEmoji_doesNotSplitSurrogatePair() {
        Long vid = insertVolunteer("截断乙");
        // 每个 🎉 是 1 个 code point / 2 个码元；600 个 emoji 必然触发截断
        String emojiReason = "🎉".repeat(600);

        assertEquals(5, pointService.record(vid, 5, PointSourceType.CORRECTION, 9570L, emojiReason,
                PointSourceType.OPERATOR_ADMIN, 9L));

        String stored = pointService.pageRecords(vid, page(), null).getRecords().get(0).getRemark();
        assertEquals(512, stored.codePointCount(0, stored.length()), "按字符数截断到列宽");
        assertTrue(stored.endsWith("…"));
        // codePoints() 会把落单的代理项原样吐出来，落在 D800–DFFF 区间即说明切断了代理对
        assertTrue(stored.codePoints().noneMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF),
                "不得留下落单的代理项（半个字符）");
        assertEquals(511, stored.codePoints().filter(cp -> cp == 0x1F389).count(), "应保留 511 个完整 emoji");
    }

    /** 调整接口层面同样要挡住键复用 */
    @Test
    void adjust_reusedRequestIdForAnotherVolunteer_rejected() {
        Long a = insertVolunteer("载荷己");
        Long b = insertVolunteer("载荷庚");
        PointAdjustDTO first = adjustDto(a, 15, "给甲");
        pointService.adjust(first, 9L);

        PointAdjustDTO reuse = adjustDto(b, 15, "给乙");
        reuse.setRequestId(first.getRequestId());
        assertThrows(BusinessException.class, () -> pointService.adjust(reuse, 9L));
        assertEquals(0, pointService.balanceOf(b));
    }

    // ---------- 总览会计口径 ----------

    /**
     * 积分修正产生的负数<b>不是消费</b>：活动 10 分修正为 5 分后，
     * 应显示「总积分 5 / 已使用 0」，而非「总积分 10 / 已使用 5」。
     */
    @Test
    void summary_correctionIsNotSpending() {
        Long vid = insertVolunteer("口径甲");
        pointService.record(vid, 10, PointSourceType.ACTIVITY, 9601L, "活动", PointSourceType.OPERATOR_SYSTEM, null);
        pointService.record(vid, -5, PointSourceType.CORRECTION, 9602L, "改为5分",
                PointSourceType.OPERATOR_ADMIN, 9L);

        PointSummaryVO s = pointService.summary(vid);
        assertEquals(5, s.getTotalEarned(), "修正应冲减累计获得");
        assertEquals(0, s.getTotalSpent(), "志愿者并未消费，已使用必须是 0");
        assertEquals(5, s.getBalance());
    }

    /** 管理员手工扣分同理不算消费 */
    @Test
    void summary_manualDeductionIsNotSpending() {
        Long vid = insertVolunteer("口径乙");
        pointService.record(vid, 40, PointSourceType.ACTIVITY, 9701L, "活动", PointSourceType.OPERATOR_SYSTEM, null);
        pointService.adjust(adjustDto(vid, -15, "违规扣分"), 9L);

        PointSummaryVO s = pointService.summary(vid);
        assertEquals(25, s.getTotalEarned());
        assertEquals(0, s.getTotalSpent());
        assertEquals(25, s.getBalance());
    }

    /** 只有消费类来源（兑换）才计入「已使用」，且恒等式 balance = earned - spent 成立 */
    @Test
    void summary_onlyExchangeCountsAsSpent() {
        Long vid = insertVolunteer("口径丙");
        pointService.record(vid, 100, PointSourceType.ACTIVITY, 9801L, "活动", PointSourceType.OPERATOR_SYSTEM, null);
        pointService.record(vid, -30, PointSourceType.EXCHANGE, 9802L, "兑换商品",
                PointSourceType.OPERATOR_ADMIN, 9L);
        pointService.record(vid, -10, PointSourceType.CORRECTION, 9803L, "修正", PointSourceType.OPERATOR_ADMIN, 9L);

        PointSummaryVO s = pointService.summary(vid);
        assertEquals(90, s.getTotalEarned(), "100 活动 − 10 修正");
        assertEquals(30, s.getTotalSpent(), "仅兑换算已使用");
        assertEquals(60, s.getBalance());
        assertEquals(s.getTotalEarned() - s.getTotalSpent(), s.getBalance(), "恒等式必须成立");
    }

    // ---------- 手工调整的目标校验 ----------

    /** 给不存在的志愿者调分会产生无人认领的孤儿流水，必须拒绝 */
    @Test
    void adjust_nonexistentVolunteer_rejected() {
        PointAdjustDTO dto = adjustDto(99999999L, 10, "给不存在的人加分");
        BusinessException ex = assertThrows(BusinessException.class, () -> pointService.adjust(dto, 9L));
        assertTrue(ex.getMessage().contains("不存在"), "实际=" + ex.getMessage());
    }

    /** 游客（未实名）尚未完成注册，给其调分没有业务含义 */
    @Test
    void adjust_unregisteredVolunteer_rejected() {
        Volunteer guest = new Volunteer();
        guest.setOpenid("openid_guest_" + System.nanoTime());
        guest.setStatus(0);
        volunteerMapper.insert(guest);   // 不设 registerTime = 游客

        PointAdjustDTO dto = adjustDto(guest.getId(), 10, "给游客加分");
        BusinessException ex = assertThrows(BusinessException.class, () -> pointService.adjust(dto, 9L));
        assertTrue(ex.getMessage().contains("实名"), "实际=" + ex.getMessage());
    }

    /** 停用账号仍可调整——手工调整的主要用途就是纠正历史账目，拦掉会让错账永远改不回来 */
    @Test
    void adjust_disabledVolunteer_allowedForCorrection() {
        Long vid = insertVolunteer("停用甲");
        Volunteer disable = new Volunteer();
        disable.setId(vid);
        disable.setStatus(1);   // 禁用
        volunteerMapper.updateById(disable);

        assertEquals(8, pointService.adjust(adjustDto(vid, 8, "补记历史积分"), 9L));
    }

    // ---------- 明细搜索 ----------

    /** 需求「积分增减明细（含搜索框）」：搜索匹配说明文字 */
    @Test
    void pageRecords_keywordMatchesRemark() {
        Long vid = insertVolunteer("搜索甲");
        pointService.record(vid, 10, PointSourceType.ACTIVITY, 9901L, "参加敬老院探访活动",
                PointSourceType.OPERATOR_SYSTEM, null);
        pointService.record(vid, 12, PointSourceType.ACTIVITY, 9902L, "参加海滩清洁活动",
                PointSourceType.OPERATOR_SYSTEM, null);

        List<PointRecordVO> hit = pointService.pageRecords(vid, page(), null, null, null, "敬老院").getRecords();
        assertEquals(1, hit.size());
        assertEquals("参加敬老院探访活动", hit.get(0).getRemark());

        assertEquals(2, pointService.pageRecords(vid, page(), null, null, null, "  ").getRecords().size(),
                "空白搜索词应视为不筛选");
        assertEquals(0, pointService.pageRecords(vid, page(), null, null, null, "查无此项").getRecords().size());
    }

    // ---------- 明细排序与时间筛选 ----------

    /**
     * 明细必须按 create_time 排序而非 id：V24 的历史回填按 (volunteer_id, attendance_id) 顺序插入，
     * 而 create_time 取自考勤更新时间，两者顺序并不一致——按 id 排会让志愿者看到时间乱跳的明细。
     */
    @Test
    void pageRecords_ordersByTimeNotId() {
        Long vid = insertVolunteer("积分申");
        // 先插入的这笔时间更晚，若按 id 倒序它会排在后面，按时间倒序才排在最前
        pointService.record(vid, 1, PointSourceType.ACTIVITY, 9301L, "较晚", PointSourceType.OPERATOR_SYSTEM, null);
        pointService.record(vid, 2, PointSourceType.ACTIVITY, 9302L, "较早", PointSourceType.OPERATOR_SYSTEM, null);
        backdate(vid, "较早", LocalDateTime.now().minusDays(3));
        backdate(vid, "较晚", LocalDateTime.now().minusDays(1));

        List<PointRecordVO> records = pointService.pageRecords(vid, page(), null).getRecords();
        assertEquals("较晚", records.get(0).getRemark(), "应按发生时间倒序，而非流水 id");
        assertEquals("较早", records.get(1).getRemark());
    }

    @Test
    void pageRecords_filtersByTimeRange() {
        Long vid = insertVolunteer("积分酉");
        pointService.record(vid, 1, PointSourceType.ACTIVITY, 9401L, "去年", PointSourceType.OPERATOR_SYSTEM, null);
        pointService.record(vid, 2, PointSourceType.ACTIVITY, 9402L, "今天", PointSourceType.OPERATOR_SYSTEM, null);
        backdate(vid, "去年", LocalDateTime.now().minusDays(400));

        List<PointRecordVO> recent = pointService.pageRecords(vid, page(), null,
                LocalDateTime.now().minusDays(7), null, null).getRecords();
        assertEquals(1, recent.size(), "时间区间应过滤掉早于起点的流水");
        assertEquals("今天", recent.get(0).getRemark());
    }

    // ---------- 辅助 ----------

    private PageQuery page() {
        PageQuery q = new PageQuery();
        q.setPage(1);
        q.setSize(50);
        return q;
    }

    /**
     * 并发跑同一个动作 {@code threads} 次并<b>收集每次的返回值</b>。
     *
     * <p>关键在于用 {@code Future.get()} 逐个取结果——任何一次抛异常都会在这里重新抛出、让用例失败。
     * 早前的写法是 catch 住异常只看最终余额，那样「6 次里 5 次失败」也能通过，等于没测。</p>
     */
    private List<Integer> runConcurrently(int threads, Supplier<Integer> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return action.get();
                }));
            }
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));   // 异常在此重新抛出，不再被吞
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 在<b>另一个线程（另一条连接、独立事务）</b>里跑完并提交，当前线程等待其完成。
     *
     * <p>用于制造「本事务快照建立之后、另一事务已提交」的时序——同线程复用同一连接是造不出来的。</p>
     */
    /** 等 latch，不把 InterruptedException 往外抛（用在事务 lambda 里，签名不允许受检异常） */
    private void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发前置条件超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void commitInOtherThread(Runnable action) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(action).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("并发前置写入失败", e);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 造一个带全新幂等键的调整入参 */
    private PointAdjustDTO adjustDto(Long volunteerId, int amount, String reason) {
        PointAdjustDTO dto = new PointAdjustDTO();
        dto.setVolunteerId(volunteerId);
        dto.setChangeAmount(amount);
        dto.setReason(reason);
        dto.setRequestId(UUID.randomUUID().toString());
        return dto;
    }

    /** 直接改流水的 create_time，用于构造「id 顺序与时间顺序不一致」的场景（回填数据就是这样） */
    private void backdate(Long volunteerId, String remark, LocalDateTime time) {
        pointRecordMapper.update(null, Wrappers.<PointRecord>lambdaUpdate()
                .set(PointRecord::getCreateTime, time)
                .eq(PointRecord::getVolunteerId, volunteerId)
                .eq(PointRecord::getRemark, remark));
    }

    private Long insertVolunteer(String name) {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime());
        v.setRealName(name);
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }
}
