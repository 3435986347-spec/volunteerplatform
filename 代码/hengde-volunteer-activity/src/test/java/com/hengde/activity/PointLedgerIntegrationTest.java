package com.hengde.activity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.dao.PointRecordMapper;
import com.hengde.activity.dto.BackfillRequestDTO;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.entity.PointRecord;
import com.hengde.activity.service.ActivityBackfillService;
import com.hengde.activity.service.ActivityChangeService;
import com.hengde.activity.service.PointService;
import com.hengde.activity.service.ServiceRecordService;
import com.hengde.activity.vo.VolunteerServiceStatsView;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 积分账本与三条业务链路的<b>集成</b>验证（V2 第 1 批修复轮）。
 *
 * <p>{@link PointServiceTest} 只证明账本自身正确；本类补的是另一半——<b>业务动作确实把账记上了</b>，
 * 且账本与既有的考勤快照口径不打架。三条写入链路：秘书部发放积分、活动补录落账、
 * 组织部改积分经部长审核。</p>
 *
 * <p>另含一条最容易被忽略的并发用例：<b>同一考勤挂两张待审的改积分申请，被并发审核</b>。
 * 两次审核 CAS 的是各自的申请行、彼此不冲突，若取考勤时用快照读，两个线程会读到同一个旧值，
 * 各自算出的增量之和与考勤快照对不上——账实分离。这类缺陷跑单线程测试永远发现不了。</p>
 *
 * <p>V24 回填语句本身另有 common 模块的 {@code V24BackfillMigrationTest} 做真实升级验证，本类不重复。</p>
 *
 * <p>MySQL + Redis 由 Testcontainers 起。<b>需本机有 Docker。</b></p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class PointLedgerIntegrationTest {

    private static final long ADMIN = 100L;
    private static final long REQUESTER = 300L;
    private static final long AUDITOR = 400L;
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final AtomicLong ACT_SEQ = new AtomicLong(System.nanoTime() % 1_000_000);

    @Autowired
    private PointService pointService;
    @Autowired
    private ServiceRecordService serviceRecordService;
    @Autowired
    private ActivityChangeService changeService;
    @Autowired
    private ActivityBackfillService backfillService;
    @Autowired
    private PointRecordMapper pointRecordMapper;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ActivitySlotMapper slotMapper;
    @Autowired
    private ActivityAttendanceMapper attendanceMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;

    // ---------- 链路一：秘书部确认 → 积分发放 ----------

    /** 发放积分必须同时落一条 source_type=活动积分 的流水，且金额与考勤快照一致 */
    @Test
    void grantPoints_writesLedger() {
        Long actId = insertActivity(20);
        Long vid = insertVolunteer("链路甲");
        Long attId = insertAttendance(actId, vid, 1);

        int award = serviceRecordService.grantPoints(attId, 0, ADMIN);

        assertEquals(award, pointService.balanceOf(vid), "余额应等于本次发放额");
        List<PointRecord> rows = ledgerOf(vid);
        assertEquals(1, rows.size(), "应恰好一条流水");
        assertEquals(PointSourceType.ACTIVITY, rows.get(0).getSourceType());
        assertEquals(attId, rows.get(0).getSourceId(), "活动积分的 source_id 应指向考勤 id");
        assertEquals(award, rows.get(0).getChangeAmount());
        assertEquals(award, attendanceMapper.selectById(attId).getPointsAward(), "考勤快照应与流水一致");
    }

    /** 重复发放被 CAS 拦下，账本也不能多记 */
    @Test
    void grantPoints_twice_ledgerNotDoubled() {
        Long actId = insertActivity(15);
        Long vid = insertVolunteer("链路乙");
        Long attId = insertAttendance(actId, vid, 1);

        int award = serviceRecordService.grantPoints(attId, 0, ADMIN);
        try {
            serviceRecordService.grantPoints(attId, 0, ADMIN);
        } catch (Exception ignored) {
            // CAS 应拦下第二次；无论抛错与否，账本都不能多记
        }
        assertEquals(award, pointService.balanceOf(vid), "重复发放不应让余额翻倍");
        assertEquals(1, ledgerOf(vid).size());
    }

    // ---------- 链路二：活动补录审核通过 → 落考勤 + 入账 ----------

    @Test
    void backfillApprove_writesLedger() {
        Long actId = insertActivity(30);
        Long slotId = insertSlot(actId, at(9), at(11));
        String phone = uniquePhone();
        Long vid = insertVolunteer("链路丙", phone, null);

        BackfillRequestDTO dto = new BackfillRequestDTO();
        dto.setPhone(phone);
        dto.setName("链路丙");
        dto.setSlotId(slotId);
        Long backfillId = backfillService.requestBackfill(actId, dto, REQUESTER);
        backfillService.approve(backfillId, "核实无误", AUDITOR);

        ActivityAttendance att = attendanceMapper.selectOne(Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getActivityId, actId)
                .eq(ActivityAttendance::getVolunteerId, vid));
        assertTrue(att.getPointsAward() > 0, "普通活动补录应发积分");
        assertEquals(att.getPointsAward(), pointService.balanceOf(vid), "补录发的分应进账本");

        List<PointRecord> rows = ledgerOf(vid);
        assertEquals(1, rows.size());
        assertEquals(PointSourceType.ACTIVITY, rows.get(0).getSourceType());
        assertEquals(att.getId(), rows.get(0).getSourceId());
    }

    // ---------- 链路三：改积分申请 → 部长审核通过 → 按差额入账 ----------

    /** 改积分记的必须是<b>差额</b>，不是新值——否则志愿者余额会凭空多出一份 */
    @Test
    void pointsChangeApprove_recordsDelta() {
        Long actId = insertActivity(10);
        Long vid = insertVolunteer("链路丁");
        Long attId = insertAttendance(actId, vid, 1);
        int award = serviceRecordService.grantPoints(attId, 0, ADMIN);

        int target = award + 40;
        Long changeId = changeService.requestChange(attId, 3, String.valueOf(target), "补发", REQUESTER);
        changeService.approve(changeId, "同意", AUDITOR);

        assertEquals(target, pointService.balanceOf(vid), "余额应等于修正后的目标值，而非叠加新值");
        assertEquals(target, attendanceMapper.selectById(attId).getPointsAward(), "考勤快照应同步");

        PointRecord correction = ledgerOf(vid).stream()
                .filter(r -> PointSourceType.CORRECTION == r.getSourceType()).findFirst().orElseThrow();
        assertEquals(40, correction.getChangeAmount(), "应记差额");
        assertEquals(changeId, correction.getSourceId(), "修正流水的 source_id 应指向变更申请 id，而非考勤 id");
        assertEquals(AUDITOR, correction.getOperatorId(), "操作人应是审核人");
    }

    /** 同一考勤被连续修正两次都要成功——若 source_id 用考勤 id 就会撞唯一键、第二次静默丢分 */
    @Test
    void pointsChange_twiceSequentially_bothApplied() {
        Long actId = insertActivity(10);
        Long vid = insertVolunteer("链路戊");
        Long attId = insertAttendance(actId, vid, 1);
        int award = serviceRecordService.grantPoints(attId, 0, ADMIN);

        Long c1 = changeService.requestChange(attId, 3, String.valueOf(award + 10), "第一次", REQUESTER);
        changeService.approve(c1, null, AUDITOR);
        Long c2 = changeService.requestChange(attId, 3, String.valueOf(award + 25), "第二次", REQUESTER);
        changeService.approve(c2, null, AUDITOR);

        assertEquals(award + 25, pointService.balanceOf(vid), "两次修正后余额应等于最终值");
        assertEquals(award + 25, attendanceMapper.selectById(attId).getPointsAward());
    }

    /**
     * <b>核心并发用例</b>：同一考勤挂两张待审改积分申请，被并发审核。
     *
     * <p>两次审核各自 CAS 自己的申请行、互不冲突，拦不住并发；若取考勤用快照读，两个线程都会读到
     * 旧值 award，分别记下 (t1-award) 和 (t2-award)，余额变成 t1+t2-award，而考勤快照只会是 t1 或 t2
     * ——账实分离。加了 {@code FOR UPDATE} 后两次审核被串行化，后者读到前者的结果，余额与快照恒等。</p>
     */
    @Test
    void concurrentPointsChangeApprove_ledgerMatchesSnapshot() throws Exception {
        Long actId = insertActivity(10);
        Long vid = insertVolunteer("链路己");
        Long attId = insertAttendance(actId, vid, 1);
        int award = serviceRecordService.grantPoints(attId, 0, ADMIN);

        int t1 = award + 2;
        int t2 = award + 5;
        Long c1 = changeService.requestChange(attId, 3, String.valueOf(t1), "并发一", REQUESTER);
        Long c2 = changeService.requestChange(attId, 3, String.valueOf(t2), "并发二", REQUESTER);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        for (Long cid : List.of(c1, c2)) {
            pool.submit(() -> {
                try {
                    start.await();
                    changeService.approve(cid, null, AUDITOR);
                } catch (Exception ignored) {
                    // 某一方因锁等待/死锁回滚是可接受的；关键是不能出现「账实分离」
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发审核超时");
        pool.shutdown();

        int snapshot = attendanceMapper.selectById(attId).getPointsAward();
        assertEquals(snapshot, pointService.balanceOf(vid),
                "账本余额必须与考勤快照一致——不一致即两次审核读到了同一个旧值");
        assertTrue(snapshot == t1 || snapshot == t2, "最终快照应是两个目标值之一，实际=" + snapshot);
    }

    // ---------- 明细可搜索：remark 必须带上可检索的业务信息 ----------

    /**
     * 活动积分的 remark 要带活动名称，否则志愿者在积分中心按活动名根本搜不到这笔分。
     */
    @Test
    void grantPoints_remarkCarriesActivityTitle() {
        Long actId = insertActivity(20);
        Long vid = insertVolunteer("备注甲");
        Long attId = insertAttendance(actId, vid, 1);
        serviceRecordService.grantPoints(attId, 0, ADMIN);

        String title = activityMapper.selectById(actId).getTitle();
        assertTrue(ledgerOf(vid).get(0).getRemark().contains(title),
                "remark 应含活动名，实际=" + ledgerOf(vid).get(0).getRemark());
        assertEquals(1, pointService.pageRecords(vid, page(), null, null, null, title).getRecords().size(),
                "应能按活动名搜到");
    }

    /**
     * 积分修正的 remark 要带上申请理由，否则「为什么改」无法检索。
     *
     * <p>此前只写「旧值 → 新值」，文档却宣称修正原因写在 remark 里——对不上。</p>
     */
    @Test
    void pointsChange_remarkCarriesReason() {
        Long actId = insertActivity(10);
        Long vid = insertVolunteer("备注乙");
        Long attId = insertAttendance(actId, vid, 1);
        int award = serviceRecordService.grantPoints(attId, 0, ADMIN);

        String reason = "现场临时增加清运任务，按加班补发";
        Long changeId = changeService.requestChange(attId, 3, String.valueOf(award + 6), reason, REQUESTER);
        changeService.approve(changeId, "同意", AUDITOR);

        PointRecord correction = ledgerOf(vid).stream()
                .filter(r -> PointSourceType.CORRECTION == r.getSourceType()).findFirst().orElseThrow();
        assertTrue(correction.getRemark().contains(reason),
                "修正 remark 应含申请理由，实际=" + correction.getRemark());
        assertEquals(1, pointService.pageRecords(vid, page(), null, null, null, "清运").getRecords().size(),
                "应能按修正理由搜到");
    }

    /**
     * 顶格长度的修正理由必须安全截断，不能让审核事务因 data too long 整体失败。
     *
     * <p>理由列 512、remark 列也是 512，而 remark 还要拼「积分修正（x → y）：」前缀，
     * 所以<b>写满 512 的理由必然溢出</b>——这正是需要截断兜底的真实场景。
     * （早前用 480 字理由，扩列到 512 后已不再触发截断，等于没测到这条路径。）</p>
     */
    @Test
    void pointsChange_maxLengthReason_doesNotBreakApproval() {
        Long actId = insertActivity(10);
        Long vid = insertVolunteer("备注丙");
        Long attId = insertAttendance(actId, vid, 1);
        int award = serviceRecordService.grantPoints(attId, 0, ADMIN);

        String longReason = "补".repeat(512);   // 顶满 reason 列宽，加前缀后必定超过 remark 的 512
        Long changeId = changeService.requestChange(attId, 3, String.valueOf(award + 3), longReason, REQUESTER);
        changeService.approve(changeId, null, AUDITOR);

        assertEquals(award + 3, attendanceMapper.selectById(attId).getPointsAward(), "审核应正常完成");
        assertEquals(award + 3, pointService.balanceOf(vid));

        PointRecord correction = ledgerOf(vid).stream()
                .filter(r -> PointSourceType.CORRECTION == r.getSourceType()).findFirst().orElseThrow();
        String remark = correction.getRemark();
        assertEquals(512, remark.codePointCount(0, remark.length()), "应截断到列宽");
        assertTrue(remark.endsWith("…"), "末位应有省略号示意被截断");
    }

    // ---------- 账本是唯一事实来源：跨域统计口径 ----------

    /**
     * 手工扣分后，「志愿者管理」列表/个人资料读到的积分必须跟着账本走。
     *
     * <p>这里曾经是断链：统计从 {@code activity_attendance.points_award} 汇总，而手工调整只写账本——
     * 管理员扣了 20 分，积分中心显示 30、资料页仍显示 50，同一个人两处对不上。</p>
     */
    @Test
    void batchStats_followsLedgerAfterManualAdjust() {
        Long actId = insertActivity(50);
        Long vid = insertVolunteer("链路庚");
        Long attId = insertAttendance(actId, vid, 1);
        int award = serviceRecordService.grantPoints(attId, 0, ADMIN);

        // 幂等键限 ASCII 安全字符集（见 PointService.REQUEST_ID_PATTERN）——中文只放 remark，不放键
        pointService.record(vid, -20, PointSourceType.MANUAL, null, "manual-deduct-" + SEQ.incrementAndGet(),
                "扣分", PointSourceType.OPERATOR_ADMIN, ADMIN);

        Map<Long, VolunteerServiceStatsView> stats = serviceRecordService.batchStatsByVolunteerIds(List.of(vid));
        assertEquals(award - 20, stats.get(vid).points(), "统计口径应取账本余额，而非考勤 points_award 之和");
        assertEquals(pointService.balanceOf(vid), stats.get(vid).points(), "两处必须显示同一个数");
        assertEquals(1, stats.get(vid).activityCount(), "活动数仍按考勤统计，不受账本影响");
    }

    /** 只有手工流水、没有任何考勤的志愿者也必须出现在统计里，否则他的积分会凭空消失 */
    @Test
    void batchStats_includesVolunteerWithLedgerButNoAttendance() {
        Long vid = insertVolunteer("链路辛");
        pointService.record(vid, 33, PointSourceType.MANUAL, null, "manual-only-" + SEQ.incrementAndGet(),
                "奖励", PointSourceType.OPERATOR_ADMIN, ADMIN);

        Map<Long, VolunteerServiceStatsView> stats = serviceRecordService.batchStatsByVolunteerIds(List.of(vid));
        VolunteerServiceStatsView s = stats.get(vid);
        assertTrue(s != null, "无考勤但有积分流水者不能被漏掉");
        assertEquals(33, s.points());
        assertEquals(0, s.activityCount());
        assertEquals(0, s.confirmedMinutes());
    }

    // ---------- helpers ----------

    private List<PointRecord> ledgerOf(Long volunteerId) {
        return pointRecordMapper.selectList(Wrappers.<PointRecord>lambdaQuery()
                .eq(PointRecord::getVolunteerId, volunteerId)
                .orderByAsc(PointRecord::getId));
    }

    private LocalDateTime at(int hour) {
        return LocalDateTime.of(2026, 3, 1, hour, 0);
    }

    private PageQuery page() {
        PageQuery q = new PageQuery();
        q.setPage(1);
        q.setSize(50);
        return q;
    }

    private Long insertActivity(int pointsBase) {
        Activity a = new Activity();
        a.setTitle("账本活动_" + ACT_SEQ.incrementAndGet());
        a.setStartTime(at(8));
        a.setEndTime(at(18));
        a.setStatus(2);              // 已结束
        a.setRunStatus(2);
        a.setIsHistorical(0);
        a.setPointsBase(pointsBase);
        activityMapper.insert(a);
        a.setSerialNo(a.getId());
        activityMapper.updateById(a);
        return a.getId();
    }

    private Long insertSlot(Long activityId, LocalDateTime start, LocalDateTime end) {
        ActivitySlot s = new ActivitySlot();
        s.setActivityId(activityId);
        s.setProjectName("项目_" + ACT_SEQ.incrementAndGet());
        s.setStartTime(start);
        s.setEndTime(end);
        s.setNeedCount(10);
        slotMapper.insert(s);
        return s.getId();
    }

    /** 造一条「已签退、秘书部已确认、积分未发放」的考勤，正好卡在发放前 */
    private Long insertAttendance(Long activityId, Long volunteerId, int attendStatus) {
        ActivityAttendance att = new ActivityAttendance();
        att.setActivityId(activityId);
        att.setSlotId(insertSlot(activityId, at(9), at(11)));   // V30
        att.setVolunteerId(volunteerId);
        att.setCheckInTime(at(9));
        att.setCheckOutTime(at(11));
        att.setServiceMinutes(120);
        att.setAttendStatus(attendStatus);
        att.setSecretaryStatus(1);
        att.setPointsStatus(0);
        att.setPointsFactor(0);
        attendanceMapper.insert(att);
        return att.getId();
    }

    private Long insertVolunteer(String name) {
        return insertVolunteer(name, null, null);
    }

    private Long insertVolunteer(String name, String phone, String idCard) {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime() + "_" + SEQ.incrementAndGet());
        v.setRealName(name);
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        if (phone != null) {
            v.setPhone(cryptoUtil.encrypt(phone));
            v.setPhoneHash(cryptoUtil.hashPhone(phone));
        }
        if (idCard != null) {
            v.setIdCardNo(cryptoUtil.encrypt(idCard));
            v.setIdCardHash(cryptoUtil.hashIdCard(idCard));
        }
        volunteerMapper.insert(v);
        return v.getId();
    }

    private String uniquePhone() {
        return "139" + String.format("%08d", (System.nanoTime() / 1000 + SEQ.incrementAndGet()) % 100000000);
    }
}
