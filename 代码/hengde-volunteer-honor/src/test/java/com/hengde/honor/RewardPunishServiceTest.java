package com.hengde.honor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.dao.ActivityViolationMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.entity.ActivityViolation;
import com.hengde.activity.service.EnrollmentService;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.service.PointService;
import com.hengde.activity.service.ViolationReviewService;
import com.hengde.auth.constant.SanctionScope;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.dao.VolunteerSanctionMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.entity.VolunteerSanction;
import com.hengde.auth.service.SanctionQueryService;
import com.hengde.auth.service.SanctionService;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.honor.dto.AppealHandleDTO;
import com.hengde.honor.dto.AppealSubmitDTO;
import com.hengde.honor.dto.RewardPunishSaveDTO;
import com.hengde.honor.entity.HonorRewardPunish;
import com.hengde.honor.service.RewardPunishService;
import com.hengde.honor.vo.RewardPunishVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 奖惩中心（V2 第 5 批）验证。<b>需本机 Docker</b>（MySQL + Redis）。
 *
 * <p>重点压住几条只在边界与异常下才现形的：审核前不可见、申诉期边界、
 * 申诉成立的积分冲正不能撞 {@code uk_source}、处置到期自动失效（不依赖定时任务）、
 * 「拒绝使用本程序」不得把申诉入口一起挡掉。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, InMemoryFileStorageConfig.class})
class RewardPunishServiceTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000 * 1000);
    private static final long ADMIN = 900L;

    @Autowired
    private RewardPunishService rewardPunishService;
    @Autowired
    private ViolationReviewService violationReviewService;
    @Autowired
    private SanctionQueryService sanctionQueryService;
    @Autowired
    private SanctionService sanctionService;
    @Autowired
    private EnrollmentService enrollmentService;
    @Autowired
    private PointService pointService;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ActivitySlotMapper slotMapper;
    @Autowired
    private ActivityViolationMapper violationMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private VolunteerSanctionMapper sanctionMapper;
    @Autowired
    private com.hengde.honor.dao.HonorRewardPunishMapper rewardPunishMapper;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    // ---------- ① 审核才可显示（Row 41 F）----------

    /** 待审核的奖惩单<b>不得</b>出现在志愿者的奖惩记录里。 */
    @Test
    void pendingRecord_isInvisibleToVolunteer_untilApproved() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(reward(vid, 200), ADMIN);

        assertTrue(rewardPunishService.myRecords(vid).isEmpty(),
                "Row 41 F：审核才可显示——待审核的不该让志愿者看到");
        assertEquals(1, rewardPunishService.adminList(new PageQuery(), vid, null, null, null, false)
                .getRecords().size(), "后台仍要看得到，否则没法审");

        rewardPunishService.approve(id, ADMIN);
        List<RewardPunishVO> mine = rewardPunishService.myRecords(vid);
        assertEquals(1, mine.size(), "通过后才显示");
        assertEquals(200, mine.get(0).getPointsDelta());
    }

    /** 驳回的单子志愿者始终看不到，积分也不入账。 */
    @Test
    void rejectedRecord_neverVisible_andNoPoints() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(reward(vid, 50), ADMIN);
        rewardPunishService.reject(id, "证据不足", ADMIN);

        assertTrue(rewardPunishService.myRecords(vid).isEmpty());
        assertEquals(0, pointService.summary(vid).getBalance(), "驳回不入账");
        assertThrows(BusinessException.class, () -> rewardPunishService.approve(id, ADMIN),
                "已裁决的单不能再审一次");
    }

    /** 未经组织部审核的现场违规，<b>不够格</b>作为开处罚单的依据。 */
    @Test
    void punish_fromUnreviewedViolation_isRejected() {
        Long vid = insertVolunteer();
        Fixture f = activityWithSlot();
        Long violationId = insertViolation(f, vid);

        RewardPunishSaveDTO dto = punish(vid, 0);
        dto.setViolationId(violationId);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> rewardPunishService.create(dto, ADMIN));
        assertTrue(ex.getMessage().contains("尚未通过"), "实际：" + ex.getMessage());

        // 审过之后就可以了，且归属以违规记录为准
        violationReviewService.approve(violationId, ADMIN);
        Long id = rewardPunishService.create(dto, ADMIN);
        HonorRewardPunish rp = rewardPunishMapper.selectById(id);
        assertEquals(vid, rp.getVolunteerId());
        assertEquals(f.activityId, rp.getActivityId(), "活动归属应从违规记录带出");
        assertEquals(f.slotId, rp.getSlotId(), "场次归属同上");
    }

    /** 同一条违规只能开一张处罚单。 */
    @Test
    void punish_sameViolationTwice_isRejected() {
        Long vid = insertVolunteer();
        Fixture f = activityWithSlot();
        Long violationId = insertViolation(f, vid);
        violationReviewService.approve(violationId, ADMIN);

        RewardPunishSaveDTO dto = punish(vid, 0);
        dto.setViolationId(violationId);
        rewardPunishService.create(dto, ADMIN);
        assertThrows(BusinessException.class, () -> rewardPunishService.create(dto, ADMIN));
    }

    /**
     * 并发下撞的是 {@code uk_active_violation} 时，必须回一句<b>人话</b>，
     * 而不是白换 5 个编号后抛出原始的 {@code DuplicateKeyException}。
     *
     * <p><b>缺陷形态</b>：{@code insertWithNewNo} 的预查是快照读，并发下两个人都能过；
     * 随后撞键的可能是编号（该重试）也可能是这条违规已开过单（重试多少次都一样）。
     * 两者靠 {@code isViolationConflict} 的<b>索引名字符串</b>区分——而那是个裸字符串：
     * 将来某个迁移重命名索引、或驱动/MySQL 换了报错格式，它会<b>静默降级</b>成「换号重试 5 次再抛」。
     * 数据不会坏，但报错从「请勿重复开单」变成一个指向编号生成的数据库异常，
     * 与证书那条撞号是同一形状。</p>
     *
     * <p><b>窗口怎么造的</b>：外层显式 RR 事务先读一次建快照，另一条连接把第一张单提交；
     * {@code create} 是 {@code REQUIRED}，加入外层事务、继承那个过期读视图 ——
     * 于是预查看不到、INSERT 撞得到，正是并发下的真实时序。不用 sleep。</p>
     *
     * <p><b>变异验证</b>：把 {@code isViolationConflict} 里的索引名改成别的字符串（模拟重命名），
     * 本用例会以 {@code DuplicateKeyException} 而不是 {@code BusinessException} 变红。</p>
     */
    @Test
    void punish_concurrentSameViolation_reportsBusinessConflictNotRawDuplicateKey() {
        Long vid = insertVolunteer();
        Fixture f = activityWithSlot();
        Long violationId = insertViolation(f, vid);
        violationReviewService.approve(violationId, ADMIN);

        RewardPunishSaveDTO dto = punish(vid, 0);
        dto.setViolationId(violationId);

        var repeatableRead = new org.springframework.transaction.support.TransactionTemplate(
                transactionTemplate.getTransactionManager());
        repeatableRead.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);

        repeatableRead.executeWithoutResult(status -> {
            assertEquals(0, countByViolation(violationId), "前置：这条违规还没开过单");

            // 另一条连接抢先开出第一张并提交
            commitInOtherThread(() -> rewardPunishService.create(dto, ADMIN));

            assertEquals(0, countByViolation(violationId),
                    "RR 快照应仍读不到刚提交的那张单，否则本用例覆盖不到目标窗口"
                            + "（预查若能看见，走的就是那句友好报错的另一条分支）");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> rewardPunishService.create(dto, ADMIN),
                    "撞 uk_active_violation 必须被翻译成业务冲突，而不是漏成数据库异常");
            assertTrue(ex.getMessage().contains("请勿重复开单"), "实际：" + ex.getMessage());

            // 内层事务异常回滚会把外层标记成 global-rollback-only，显式标记以免提交时抛
            // UnexpectedRollbackException 盖掉上面的断言结果
            status.setRollbackOnly();
        });
    }

    /**
     * 但<b>被驳回</b>之后必须能重新开单（V35）。
     *
     * <p>缺陷形态：{@code uk_active_violation} 原先只按 {@code is_deleted} 释放占位，
     * 驳回的单照样占着那条违规；而系统里没有修改 / 删除 / 重提入口，
     * 于是开单人写错了类别或说明、组织部驳回并写明原因之后，那条违规<b>再也开不出第二张单</b>——
     * {@code reject} 强制填写的那条原因本就是给开单人据以改正的，改正却无路可走。</p>
     *
     * <p>把 V35 的生成列表达式改回 {@code CASE WHEN is_deleted = 0 ...}，或把
     * {@code insertWithNewNo} 的预查改回不排除驳回，本用例都必红（前者报数据库撞键，
     * 后者报「请勿重复开单」）。</p>
     *
     * <p>⚠️ 「驳回后可重开」是<b>推论</b>，见《协会待确认清单》第 9 条。</p>
     */
    @Test
    void punish_afterRejection_canBeReissuedForTheSameViolation() {
        Long vid = insertVolunteer();
        Fixture f = activityWithSlot();
        Long violationId = insertViolation(f, vid);
        violationReviewService.approve(violationId, ADMIN);

        RewardPunishSaveDTO dto = punish(vid, 0);
        dto.setViolationId(violationId);
        Long first = rewardPunishService.create(dto, ADMIN);
        rewardPunishService.reject(first, "类别填错了，请重开", ADMIN);

        Long second = rewardPunishService.create(dto, ADMIN);
        assertNotEquals(first, second, "驳回之后应当能就同一条违规重新开单");

        // 但「同时只能有一张未被驳回的单」这条不变量仍在
        assertThrows(BusinessException.class, () -> rewardPunishService.create(dto, ADMIN),
                "第二张还在待审核，不该再开第三张");
    }

    /** 奖励不能附带处置——一张「奖励」单把人限制住，而记录上他是被表扬的。 */
    @Test
    void reward_withSanction_isRejected() {
        Long vid = insertVolunteer();
        RewardPunishSaveDTO dto = reward(vid, 100);
        dto.setSanctionScope(SanctionScope.ACTIVITY);
        assertThrows(BusinessException.class, () -> rewardPunishService.create(dto, ADMIN));
    }

    /** 符号写反会让「处罚」给人加分，而列表上仍显示为处罚。 */
    @Test
    void pointsDelta_signMustMatchType() {
        Long vid = insertVolunteer();
        assertThrows(BusinessException.class, () -> rewardPunishService.create(reward(vid, -10), ADMIN));
        assertThrows(BusinessException.class, () -> rewardPunishService.create(punish(vid, 10), ADMIN));
    }

    // ---------- ② 处置的执行与到期 ----------

    /** 审核通过 → 处置生效 → 报名与签到都被挡住。 */
    @Test
    void approvedPunish_imposesSanction_andBlocksEnrollment() {
        Long vid = insertVolunteer();
        assertFalse(sanctionQueryService.isRestricted(vid, SanctionScope.ACTIVITY), "前置：未受限");

        RewardPunishSaveDTO dto = punish(vid, 0);
        dto.setSanctionScope(SanctionScope.ACTIVITY);
        dto.setSanctionDays(7);
        Long id = rewardPunishService.create(dto, ADMIN);

        assertFalse(sanctionQueryService.isRestricted(vid, SanctionScope.ACTIVITY),
                "审核通过前处置不得生效——待审核期间它只是一张草稿");

        rewardPunishService.approve(id, ADMIN);
        assertTrue(sanctionQueryService.isRestricted(vid, SanctionScope.ACTIVITY));

        Fixture f = activityWithSlot();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> enrollmentService.enroll(f.activityId, List.of(f.slotId), vid));
        assertTrue(ex.getMessage().contains("限制参加活动"), "实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("申诉"), "报错要告诉他还能申诉，实际：" + ex.getMessage());
    }

    /**
     * <b>到期自动失效，不依赖任何定时任务。</b>
     *
     * <p>若靠 cron 把到期的处置改成失效，任务漏跑一次处罚就会超期继续生效，
     * 而「到期即恢复」是对志愿者的承诺。这里直接把到期时刻改到过去，
     * 不跑任何任务，断言闸门立刻放行。</p>
     */
    @Test
    void sanction_expiresByTime_withoutAnyScheduledJob() {
        Long vid = insertVolunteer();
        RewardPunishSaveDTO dto = punish(vid, 0);
        dto.setSanctionScope(SanctionScope.ACTIVITY);
        dto.setSanctionDays(7);
        Long id = rewardPunishService.create(dto, ADMIN);
        rewardPunishService.approve(id, ADMIN);
        assertTrue(sanctionQueryService.isRestricted(vid, SanctionScope.ACTIVITY));

        VolunteerSanction s = sanctionMapper.selectOne(Wrappers.<VolunteerSanction>lambdaQuery()
                .eq(VolunteerSanction::getVolunteerId, vid));
        VolunteerSanction patch = new VolunteerSanction();
        patch.setId(s.getId());
        patch.setExpireTime(LocalDateTime.now().minusSeconds(1));
        sanctionMapper.updateById(patch);

        assertFalse(sanctionQueryService.isRestricted(vid, SanctionScope.ACTIVITY),
                "到期即放行——状态位仍是「生效中」，判定必须按时间现算");
        assertEquals(VolunteerSanction.STATUS_ACTIVE, sanctionMapper.selectById(s.getId()).getStatus(),
                "确认没有任何任务改过状态位，放行纯粹来自时间比较");
    }

    /** 「拒绝使用本程序」蕴含所有能力域——否则它反而比「限制参加活动」管得少。 */
    @Test
    void allScopeSanction_impliesEveryOtherScope() {
        Long vid = insertVolunteer();
        RewardPunishSaveDTO dto = punish(vid, 0);
        dto.setSanctionScope(SanctionScope.ALL);
        Long id = rewardPunishService.create(dto, ADMIN);
        rewardPunishService.approve(id, ADMIN);

        assertTrue(sanctionQueryService.isRestricted(vid, SanctionScope.ACTIVITY));
        assertTrue(sanctionQueryService.isRestricted(vid, SanctionScope.COMMUNITY));

        // 但【不能】挡住奖惩记录与申诉——被罚得最重的人恰恰是最需要申诉的那个
        assertEquals(1, rewardPunishService.myRecords(vid).size(),
                "拒绝使用本程序不得连奖惩记录一起挡掉，否则他看不到自己被罚了什么");
        assertTrue(rewardPunishService.myRecords(vid).get(0).getAppealable(),
                "更不能挡掉申诉入口，那会让申诉权形同虚设");
    }

    // ---------- ③ 申诉 ----------

    /** 只有处罚可申诉：P109 的申诉按钮只画在处罚上，奖励卡片只有「查看详情」。 */
    @Test
    void reward_cannotBeAppealed() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(reward(vid, 100), ADMIN);
        rewardPunishService.approve(id, ADMIN);

        assertFalse(rewardPunishService.myRecords(vid).get(0).getAppealable());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> rewardPunishService.appeal(id, vid, appealDto()));
        assertTrue(ex.getMessage().contains("无需申诉"), "实际：" + ex.getMessage());
    }

    /** 申诉期是 7 天：过了截止时刻就不能再申诉。 */
    @Test
    void appeal_afterDeadline_isRejected() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punish(vid, 0), ADMIN);
        rewardPunishService.approve(id, ADMIN);

        HonorRewardPunish rp = rewardPunishMapper.selectById(id);
        assertNotNull(rp.getAppealDeadline(), "审核通过时必须把截止时刻写死");
        assertTrue(rp.getAppealDeadline().isAfter(LocalDateTime.now().plusDays(6)), "默认 7 天");

        HonorRewardPunish patch = new HonorRewardPunish();
        patch.setId(id);
        patch.setAppealDeadline(LocalDateTime.now().minusSeconds(1));
        rewardPunishMapper.updateById(patch);

        assertFalse(rewardPunishService.myRecords(vid).get(0).getAppealable(),
                "过期后前端不该再显示申诉按钮");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> rewardPunishService.appeal(id, vid, appealDto()));
        assertTrue(ex.getMessage().contains("申诉期"), "实际：" + ex.getMessage());
    }

    /** 别人的单子不能申诉，且不得泄露「这张单是否存在」。 */
    @Test
    void appeal_othersRecord_isRejected() {
        Long vid = insertVolunteer();
        Long intruder = insertVolunteer();
        Long id = rewardPunishService.create(punish(vid, 0), ADMIN);
        rewardPunishService.approve(id, ADMIN);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> rewardPunishService.appeal(id, intruder, appealDto()));
        assertEquals("奖惩记录不存在", ex.getMessage(), "不得区分「不存在」与「不是你的」");
    }

    /** 重复提交申诉不得覆盖第一次的理由与时间。 */
    @Test
    void appeal_twice_isRejected() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punish(vid, 0), ADMIN);
        rewardPunishService.approve(id, ADMIN);
        rewardPunishService.appeal(id, vid, appealDto());

        assertThrows(BusinessException.class, () -> rewardPunishService.appeal(id, vid, appealDto()));
    }

    /**
     * <b>申诉成立：撤销处置 + 冲正积分。</b>
     *
     * <p>冲正走反向流水而不是改原始流水（账本追加型），且反向流水<b>不能复用
     * {@code source_id}</b>——{@code uk_source(6, id)} 已被审核通过那笔占住，
     * 再写一笔必然撞键。故走 {@code source_id = null + requestId}。</p>
     */
    @Test
    void appealUpheld_liftsSanction_andReversesPointsWithoutKeyClash() {
        Long vid = insertVolunteer();
        RewardPunishSaveDTO dto = punish(vid, -50);
        dto.setSanctionScope(SanctionScope.ACTIVITY);
        dto.setSanctionDays(7);
        Long id = rewardPunishService.create(dto, ADMIN);
        rewardPunishService.approve(id, ADMIN);

        assertEquals(-50, pointService.summary(vid).getBalance(), "审核通过即扣分");
        assertTrue(sanctionQueryService.isRestricted(vid, SanctionScope.ACTIVITY));

        rewardPunishService.appeal(id, vid, appealDto());
        AppealHandleDTO handle = new AppealHandleDTO();
        handle.setUpheld(true);
        handle.setResult("经核实确系误记，撤销处罚");
        rewardPunishService.handleAppeal(id, handle, ADMIN);

        assertEquals(0, pointService.summary(vid).getBalance(), "扣的分要退回去");
        // 【断言 records 而不是 total】MyBatis-Plus 的分页拦截器只配在 api 模块
        // （MybatisPlusConfig），模块测试的上下文里没有它，selectPage 不发 count 查询、
        // total 恒为 0。既有用例也都只断言 records，这里跟随同一习惯。
        var flows = pointService.pageRecords(vid, new PageQuery(), null).getRecords();
        assertEquals(2, flows.size(),
                "必须是两笔流水（原始 + 反向），不是把原始那笔改掉——账本是追加型");
        assertEquals(50, flows.stream().mapToInt(r -> r.getChangeAmount()).max().orElseThrow(),
                "反向流水是 +50");
        assertEquals(-50, flows.stream().mapToInt(r -> r.getChangeAmount()).min().orElseThrow(),
                "原始那笔仍是 -50，没有被改掉");
        assertFalse(sanctionQueryService.isRestricted(vid, SanctionScope.ACTIVITY), "处置要一并撤销");
        assertEquals(VolunteerSanction.STATUS_LIFTED,
                sanctionMapper.selectOne(Wrappers.<VolunteerSanction>lambdaQuery()
                        .eq(VolunteerSanction::getVolunteerId, vid)).getStatus());
    }

    /** 申诉驳回：维持原处罚，不退分、不解除。 */
    @Test
    void appealRejected_keepsPunishmentIntact() {
        Long vid = insertVolunteer();
        RewardPunishSaveDTO dto = punish(vid, -30);
        dto.setSanctionScope(SanctionScope.ACTIVITY);
        dto.setSanctionDays(3);
        Long id = rewardPunishService.create(dto, ADMIN);
        rewardPunishService.approve(id, ADMIN);
        rewardPunishService.appeal(id, vid, appealDto());

        AppealHandleDTO handle = new AppealHandleDTO();
        handle.setUpheld(false);
        handle.setResult("现场有多人证实，维持原处罚");
        rewardPunishService.handleAppeal(id, handle, ADMIN);

        assertEquals(-30, pointService.summary(vid).getBalance(), "驳回不退分");
        assertTrue(sanctionQueryService.isRestricted(vid, SanctionScope.ACTIVITY), "驳回不解除处置");
        assertThrows(BusinessException.class, () -> rewardPunishService.handleAppeal(id, handle, ADMIN),
                "已受理的申诉不能再受理一次");
    }

    // ---------- ④ 违规审核对志愿者可见性的收口 ----------

    /** 驳回违规必须填原因——否则负责人无从改正，也无从申辩。 */
    @Test
    void violationReject_requiresReason() {
        Long vid = insertVolunteer();
        Fixture f = activityWithSlot();
        Long violationId = insertViolation(f, vid);
        assertThrows(BusinessException.class, () -> violationReviewService.reject(violationId, "  ", ADMIN));
        violationReviewService.reject(violationId, "记错人了", ADMIN);
        assertThrows(BusinessException.class, () -> violationReviewService.approve(violationId, ADMIN),
                "已裁决的违规不能再审一次");
    }

    /** 审核队列缺省只看待审核的。 */
    @Test
    void violationQueue_defaultsToPending() {
        Long vid = insertVolunteer();
        Fixture f = activityWithSlot();
        Long violationId = insertViolation(f, vid);

        var pending = violationReviewService.list(new PageQuery(), null, f.activityId);
        assertEquals(1, pending.getRecords().size());
        assertEquals(f.activityId, pending.getRecords().get(0).getActivityId(), "队列要带活动，否则审的人不知道是哪场");
        assertNotNull(pending.getRecords().get(0).getActivityTitle());

        violationReviewService.approve(violationId, ADMIN);
        assertTrue(violationReviewService.list(new PageQuery(), null, f.activityId).getRecords().isEmpty(),
                "审完就该从待办队列里消失");
    }

    // ---------- ⑤ 评审补正的承重回归 ----------

    /** 报名不止一个入口：代报名与后台补录都必须挡住处置，否则同组的人替他报一次就绕过去了。 */
    @Test
    void sanction_blocksProxyAndManualEnroll_notJustSelfService() {
        Long vid = insertVolunteer();
        RewardPunishSaveDTO dto = punish(vid, 0);
        dto.setSanctionScope(SanctionScope.ACTIVITY);
        dto.setSanctionDays(7);
        rewardPunishService.approve(rewardPunishService.create(dto, ADMIN), ADMIN);

        Fixture f = activityWithSlot();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> enrollmentService.manualEnroll(f.activityId, vid, List.of(f.slotId), ADMIN),
                "后台补录不得悄悄抵消一条正在执行的处罚");
        assertTrue(ex.getMessage().contains("限制参加活动"), "实际：" + ex.getMessage());
    }

    /** 志愿者不存在时不得开单——奖惩单没有外键，id 打错一位就会开出一张无主的单。 */
    @Test
    void create_forNonexistentVolunteer_isRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> rewardPunishService.create(reward(999_999_999L, 10), ADMIN));
        assertTrue(ex.getMessage().contains("不存在"), "实际：" + ex.getMessage());
    }

    /** 积分幅度必须有界：-Integer.MIN_VALUE 仍是 MIN_VALUE，那张单永远冲正不回来。 */
    @Test
    void create_withUnboundedPoints_isRejected() {
        Long vid = insertVolunteer();
        assertThrows(BusinessException.class,
                () -> rewardPunishService.create(punish(vid, Integer.MIN_VALUE), ADMIN));
        assertThrows(BusinessException.class,
                () -> rewardPunishService.create(reward(vid, Integer.MAX_VALUE), ADMIN));
    }

    /** 只填天数不填范围 = 一条「有期限但什么也不限制」的单；天数也必须有上限。 */
    @Test
    void create_sanctionDaysWithoutScope_orAbsurdDays_isRejected() {
        Long vid = insertVolunteer();
        RewardPunishSaveDTO noScope = punish(vid, 0);
        noScope.setSanctionDays(7);
        assertThrows(BusinessException.class, () -> rewardPunishService.create(noScope, ADMIN));

        RewardPunishSaveDTO tooLong = punish(vid, 0);
        tooLong.setSanctionScope(SanctionScope.ACTIVITY);
        tooLong.setSanctionDays(Integer.MAX_VALUE);
        assertThrows(BusinessException.class, () -> rewardPunishService.create(tooLong, ADMIN),
                "now.plusDays(Integer.MAX_VALUE) 会直接抛 DateTimeException");
    }

    /**
     * 冲正幂等键前缀系统保留——<b>大小写与排序规则等价都不能绕过</b>。
     *
     * <p>抢占成功的后果是<b>那张单的申诉再也办不下去</b>：日后申诉成立时，反向流水撞上这条
     * 已存在的键，载荷复核发现来源码不同（手工 5 vs 奖惩 6）而抛「积分入账冲突」，
     * 整个受理事务回滚——处置没解除、分没退，直到有人手工清掉那条占位流水。
     * （不是「静默跳过、只解除处置不退分」的半提交：复核会拦下来。）</p>
     *
     * <p>忽略大小写只堵住了一半。{@code utf8mb4_0900_ai_ci} 还<b>忽略重音</b>：
     * 库里 {@code sýs:…} 与 {@code sys:…} 是同一个键，而 Java 无论区不区分大小写都认为它们不同。
     * 正解不是在 Java 里追着枚举排序规则的等价类（重音、全角、尾空格…列不完），
     * 而是把幂等键限死在 ASCII 安全字符集内，并由 V33 把该列改成 {@code utf8mb4_0900_bin}
     * 让「库里的相等」与「Java 的相等」重合。</p>
     */
    @Test
    void revertRequestPrefix_isReserved_againstCollationEquivalents() {
        Long vid = insertVolunteer();
        for (String key : new String[]{"sys:rp-revert:1", "SYS:RP-REVERT:1", "Sys:Rp-Revert:1"}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> pointService.record(vid, 5, PointSourceType.MANUAL, null, key,
                            "手工调整", PointSourceType.OPERATOR_ADMIN, ADMIN),
                    "该前缀必须被拒：" + key);
            assertTrue(ex.getMessage().contains("系统保留"), "实际：" + ex.getMessage());
        }
        // 重音 / 全角 / 尾空格 / 非 ASCII：库里可能与保留前缀等价，Java 看着不等价
        for (String key : new String[]{"sýs:rp-revert:1", "ｓｙｓ:rp-revert:1",
                "sys:rp-revert:1 ", "系统:rp-revert:1"}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> pointService.record(vid, 5, PointSourceType.MANUAL, null, key,
                            "手工调整", PointSourceType.OPERATOR_ADMIN, ADMIN),
                    "排序规则可能视其与保留前缀等价，必须被拒：" + key);
            assertTrue(ex.getMessage().contains("系统保留") || ex.getMessage().contains("幂等键只能"),
                    "实际：" + ex.getMessage());
        }
        // 正常的手工调整不受影响（UUID 形态落在允许字符集内）
        pointService.record(vid, 5, PointSourceType.MANUAL, null, "uuid-" + SEQ.incrementAndGet(),
                "手工调整", PointSourceType.OPERATOR_ADMIN, ADMIN);
        assertEquals(5, pointService.summary(vid).getBalance());
    }

    /** 审核时必须再核一次志愿者还在——开单与审核之间可能隔着几天。 */
    @Test
    void approve_afterVolunteerGone_isRejected() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punish(vid, -10), ADMIN);
        volunteerMapper.deleteById(vid);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> rewardPunishService.approve(id, ADMIN));
        assertTrue(ex.getMessage().contains("已不存在"), "实际：" + ex.getMessage());
    }

    /**
     * 【注销 ≠ 删除】上一条用的是 {@code deleteById}（行没了），而<b>注销是行还在、
     * {@code status=2}</b>——「非 null 就放行」恰恰漏的就是这一格：
     * 一个已注销的账号照样能被入账积分、被施加一条没有对象的处置。
     */
    @Test
    void approve_afterVolunteerDeregistered_isRejected() {
        Long vid = insertVolunteer();
        // 【必须带处置】否则下面那句 isRestricted 断言是空的：不带 scope 时本来就不会施加处置，
        // 去掉修复也照样绿。带上之后它才真的在证明「审核被拦下 → 处置没落地」。
        RewardPunishSaveDTO dto = punish(vid, -10);
        dto.setSanctionScope(SanctionScope.ACTIVITY);
        dto.setSanctionDays(7);
        Long id = rewardPunishService.create(dto, ADMIN);
        Volunteer gone = new Volunteer();
        gone.setId(vid);
        gone.setStatus(UserStatus.DELETED);
        volunteerMapper.updateById(gone);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> rewardPunishService.approve(id, ADMIN));
        assertTrue(ex.getMessage().contains("已注销"), "实际：" + ex.getMessage());
        assertEquals(0, pointService.summary(vid).getBalance(), "不得入账");
        assertFalse(sanctionQueryService.isRestricted(vid, SanctionScope.ACTIVITY), "不得施加处置");
    }

    /**
     * 被<b>禁用</b>（{@code status=1}）的账号：<b>奖励与处罚都拒绝</b>。
     *
     * <p><b>🔁 这条口径在第 6 轮评审被推翻过一次。</b>原先是「处罚放行」，理由是
     * 「封禁不该成为免责——不然把人禁用一下就能拦下在途的处罚单」。
     * 它漏看的是执行侧：{@code SaTokenConfigure} 对 {@code status != NORMAL} 的账号
     * 拦掉全部 {@code /v/**}，包括奖惩列表与申诉入口。于是通过审核＝
     * <b>处罚立刻生效、而 7 天申诉期在他够不到的地方流逝</b>，禁用超过 7 天申诉权就没了。
     * 「被罚得最重的人恰恰成了唯一无法申诉的人」正是 {@code SanctionScope#ALL}
     * 那条注释明确拒绝的形态（Row 41 F 给的是申诉期，不是倒计时）。</p>
     *
     * <p>原先的顾虑并没有落空：单子仍在待审核队列里，账号恢复正常后照常可以通过，
     * 而且申诉期从那时才开始算。</p>
     *
     * <p>⚠️ 仍是<b>推论</b>，不是需求原文——Row 41 / Row 73 / P109 都没写被禁用账号的奖惩口径。
     * 协会另有口径时改 {@code RewardPunishService.requireApprovableVolunteer} 一处即可。</p>
     */
    @Test
    void approve_afterVolunteerBanned_blocksBothRewardAndPunish() {
        Long vid = insertVolunteer();
        Long rewardId = rewardPunishService.create(reward(vid, 20), ADMIN);
        RewardPunishSaveDTO punishDto = punish(vid, -10);
        punishDto.setSanctionScope(SanctionScope.ACTIVITY);
        punishDto.setSanctionDays(7);
        Long punishId = rewardPunishService.create(punishDto, ADMIN);
        Volunteer banned = new Volunteer();
        banned.setId(vid);
        banned.setStatus(UserStatus.BANNED);
        volunteerMapper.updateById(banned);

        BusinessException rewardEx = assertThrows(BusinessException.class,
                () -> rewardPunishService.approve(rewardId, ADMIN));
        assertTrue(rewardEx.getMessage().contains("已禁用"), "实际：" + rewardEx.getMessage());

        BusinessException punishEx = assertThrows(BusinessException.class,
                () -> rewardPunishService.approve(punishId, ADMIN),
                "禁用期间通过处罚 = 申诉期在志愿者够不到的地方流逝");
        assertTrue(punishEx.getMessage().contains("已禁用"), "实际：" + punishEx.getMessage());
        assertEquals(0, pointService.summary(vid).getBalance(), "不得入账");
        assertFalse(sanctionQueryService.isRestricted(vid, SanctionScope.ACTIVITY), "不得施加处置");

        // 恢复正常后照常可以通过——「拦下在途处罚单」这个顾虑不成立，单子一直在队列里
        Volunteer restored = new Volunteer();
        restored.setId(vid);
        restored.setStatus(UserStatus.NORMAL);
        volunteerMapper.updateById(restored);
        rewardPunishService.approve(punishId, ADMIN);
        assertEquals(-10, pointService.summary(vid).getBalance(), "恢复后处罚照常成立");
    }

    /** 已注销的账号连开单都不该开：那张单永远审不过，留着只是一条谁也处理不掉的待办。 */
    @Test
    void create_forDeregisteredVolunteer_isRejected() {
        Long vid = insertVolunteer();
        Volunteer gone = new Volunteer();
        gone.setId(vid);
        gone.setStatus(UserStatus.DELETED);
        volunteerMapper.updateById(gone);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> rewardPunishService.create(punish(vid, -10), ADMIN));
        assertTrue(ex.getMessage().contains("已注销"), "实际：" + ex.getMessage());
    }

    /**
     * 天数上限必须由<b>写处置的那一层</b>兜住，而不只是开单那一层。
     *
     * <p>{@code SanctionService.impose} 是公共写入口；上一轮只在 {@code RewardPunishService}
     * 加了 3650 的判断，任何绕过它的路径（内部调用、数据修复、下一批新入口）仍能落进一个
     * {@code plusDays} 会抛 {@code DateTimeException} 的值——那时报的是 500。</p>
     */
    @Test
    void impose_daysOutOfRange_isRejectedAtWriteLayer() {
        Long vid = insertVolunteer();
        for (Integer days : new Integer[]{0, -1, Integer.MAX_VALUE,
                VolunteerSanction.MAX_SANCTION_DAYS + 1}) {
            assertThrows(BusinessException.class,
                    () -> sanctionService.impose(vid, VolunteerSanction.SOURCE_REWARD_PUNISH,
                            80_001L, SanctionScope.ACTIVITY, days),
                    "越界天数必须在写入层被拒：" + days);
        }
        // 边界值本身合法
        assertNotNull(sanctionService.impose(vid, VolunteerSanction.SOURCE_REWARD_PUNISH, 80_002L,
                SanctionScope.ACTIVITY, VolunteerSanction.MAX_SANCTION_DAYS));
    }


    /** 只有申诉受理权的人看不到待审核草稿与别人尚未批的处罚。 */
    @Test
    void appealOnlyViewer_seesOnlyAppealedOrders() {
        Long vid = insertVolunteer();
        Long draft = rewardPunishService.create(punish(vid, 0), ADMIN);
        Long appealed = rewardPunishService.create(punish(vid, 0), ADMIN);
        rewardPunishService.approve(appealed, ADMIN);
        rewardPunishService.appeal(appealed, vid, appealDto());

        var full = rewardPunishService.adminList(new PageQuery(), vid, null, null, null, false);
        assertEquals(2, full.getRecords().size(), "有管理权的人看得到两张");

        var narrowed = rewardPunishService.adminList(new PageQuery(), vid, null, null, null, true);
        assertEquals(1, narrowed.getRecords().size(), "只有受理权的人只看得到进入过申诉流程的那张");
        assertEquals(appealed, narrowed.getRecords().get(0).getId());
        assertTrue(narrowed.getRecords().stream().noneMatch(r -> r.getId().equals(draft)));
    }

    // ---------- 夹具 ----------

    private record Fixture(Long activityId, Long slotId) {
    }

    private RewardPunishSaveDTO reward(Long vid, int points) {
        RewardPunishSaveDTO dto = new RewardPunishSaveDTO();
        dto.setVolunteerId(vid);
        dto.setType(HonorRewardPunish.TYPE_REWARD);
        dto.setCategory("积极参加活动");
        dto.setTitle("推荐评选雷州市优秀共青团员");
        dto.setPointsDelta(points);
        return dto;
    }

    private RewardPunishSaveDTO punish(Long vid, int points) {
        RewardPunishSaveDTO dto = new RewardPunishSaveDTO();
        dto.setVolunteerId(vid);
        dto.setType(HonorRewardPunish.TYPE_PUNISH);
        dto.setCategory("违规玩手机");
        dto.setDescription("您在【测试活动】中因多次玩手机被处罚，请您下次注意");
        dto.setPointsDelta(points);
        return dto;
    }

    /**
     * 用 JdbcTemplate 直查：走本事务连接（同一读视图），但每次都真的发 SQL，绕开 MyBatis 一级缓存。
     * 这条断言要证明的正是「快照读不到」，用带缓存的通道等于测了个假象。
     */
    private int countByViolation(Long violationId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM honor_reward_punish WHERE violation_id = ? AND is_deleted = 0",
                Integer.class, violationId);
        return n == null ? 0 : n;
    }

    /** 在另一条连接上跑并提交——制造「本事务快照之后别人提交了」这个窗口。 */
    private void commitInOtherThread(Runnable action) {
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            pool.submit(action).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("并发前置写入失败", e);
        } finally {
            pool.shutdownNow();
        }
    }

    private static AppealSubmitDTO appealDto() {
        AppealSubmitDTO dto = new AppealSubmitDTO();
        dto.setReason("当时在接家长电话，并非玩手机");
        return dto;
    }

    private Long insertVolunteer() {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_rp_" + System.nanoTime() + SEQ.incrementAndGet());
        v.setRealName("奖惩志愿者");
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private Fixture activityWithSlot() {
        Activity a = new Activity();
        a.setTitle("奖惩测试活动_" + System.nanoTime());
        a.setStartTime(LocalDateTime.now().plusDays(1));
        a.setEndTime(LocalDateTime.now().plusDays(1).plusHours(3));
        a.setStatus(1);
        a.setRunStatus(0);
        a.setNeedAudit(0);
        a.setMinProjects(0);
        a.setRequireMinJoinCount(0);
        a.setPointsBase(100);
        a.setLeaderMultiplier(new BigDecimal("1.4"));
        a.setManagerMultiplier(new BigDecimal("1.2"));
        activityMapper.insert(a);
        a.setSerialNo(a.getId());
        activityMapper.updateById(a);

        ActivitySlot s = new ActivitySlot();
        s.setActivityId(a.getId());
        s.setProjectName("岗位_" + System.nanoTime());
        s.setStartTime(a.getStartTime());
        s.setEndTime(a.getEndTime());
        s.setNeedCount(10);
        slotMapper.insert(s);
        return new Fixture(a.getId(), s.getId());
    }

    private Long insertViolation(Fixture f, Long volunteerId) {
        ActivityViolation v = new ActivityViolation();
        v.setActivityId(f.activityId);
        v.setSlotId(f.slotId);
        v.setVolunteerId(volunteerId);
        v.setViolationType(1);
        v.setDescription("玩手机");
        v.setRecordedBy(1L);
        v.setRecordedTime(LocalDateTime.now());
        v.setReviewStatus(ActivityViolation.REVIEW_PENDING);
        violationMapper.insert(v);
        return v.getId();
    }
}
