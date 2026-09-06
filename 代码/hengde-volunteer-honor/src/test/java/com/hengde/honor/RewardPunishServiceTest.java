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
import com.hengde.auth.entity.VolunteerNotification;
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
    private com.hengde.auth.service.NotificationService notificationService;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    // ---------- ① 审核才可显示（Row 41 F）----------

    /** 待审核的奖惩单<b>不得</b>出现在志愿者的奖惩记录里。 */
    @Test
    void pendingRecord_isInvisibleToVolunteer_untilApproved() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(reward(vid, 200), ADMIN, false);

        assertTrue(myRecords(vid).isEmpty(),
                "Row 41 F：审核才可显示——待审核的不该让志愿者看到");
        assertEquals(1, rewardPunishService.adminList(new PageQuery(), vid, null, null, null, false)
                .getRecords().size(), "后台仍要看得到，否则没法审");

        approveFully(id, ADMIN);
        List<RewardPunishVO> mine = myRecords(vid);
        assertEquals(1, mine.size(), "通过后才显示");
        assertEquals(200, mine.get(0).getPointsDelta());
    }

    /** 驳回的单子志愿者始终看不到，积分也不入账。 */
    @Test
    void rejectedRecord_neverVisible_andNoPoints() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(reward(vid, 50), ADMIN, false);
        rewardPunishService.reject(id, "证据不足", ADMIN);

        assertTrue(myRecords(vid).isEmpty());
        assertEquals(0, pointService.summary(vid).getBalance(), "驳回不入账");
        assertThrows(BusinessException.class, () -> approveFully(id, ADMIN),
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
                () -> rewardPunishService.create(dto, ADMIN, false));
        assertTrue(ex.getMessage().contains("尚未通过"), "实际：" + ex.getMessage());

        // 审过之后就可以了，且归属以违规记录为准
        violationReviewService.approve(violationId, ADMIN);
        Long id = rewardPunishService.create(dto, ADMIN, false);
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
        rewardPunishService.create(dto, ADMIN, false);
        assertThrows(BusinessException.class, () -> rewardPunishService.create(dto, ADMIN, false));
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
            commitInOtherThread(() -> rewardPunishService.create(dto, ADMIN, false));

            assertEquals(0, countByViolation(violationId),
                    "RR 快照应仍读不到刚提交的那张单，否则本用例覆盖不到目标窗口"
                            + "（预查若能看见，走的就是那句友好报错的另一条分支）");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> rewardPunishService.create(dto, ADMIN, false),
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
     * <p>✅ 「驳回后可重开」原为推论，已由协会 2026-08-11 答复第 8 条确认
     * 「处罚单被驳回或申诉成立后…可以给他开第二张」。</p>
     */
    @Test
    void punish_afterRejection_canBeReissuedForTheSameViolation() {
        Long vid = insertVolunteer();
        Fixture f = activityWithSlot();
        Long violationId = insertViolation(f, vid);
        violationReviewService.approve(violationId, ADMIN);

        RewardPunishSaveDTO dto = punish(vid, 0);
        dto.setViolationId(violationId);
        Long first = rewardPunishService.create(dto, ADMIN, false);
        rewardPunishService.reject(first, "类别填错了，请重开", ADMIN);

        Long second = rewardPunishService.create(dto, ADMIN, false);
        assertNotEquals(first, second, "驳回之后应当能就同一条违规重新开单");

        // 但「同时只能有一张未被驳回的单」这条不变量仍在
        assertThrows(BusinessException.class, () -> rewardPunishService.create(dto, ADMIN, false),
                "第二张还在待审核，不该再开第三张");
    }

    /**
     * <b>申诉成立</b>之后，同一条违规可以重新开单（V38）。
     *
     * <p><b>需求出处</b>：协会 2026-08-11 答复第 8 条「处罚单被驳回或申诉成立后：用户申诉成立
     * 但觉得不惩罚不行，则可以给他开第二张轻一点的处罚单」。V35 时期刻意让申诉成立的单继续占位
     * （理由是「成立过又被推翻，再罚一次等于二次处罚」），那是<b>推论</b>，本版按裁决改口径。</p>
     *
     * <p><b>第二张故意开得更重（-100 vs -50）</b>，不是笔误：协会那句话说的是「轻一点」，
     * 而系统<b>不判定轻重</b>——轻重跨类别不可比，硬拦会挡住合理场景（换一个更贴切的违规类别重开，
     * 扣分未必更少，但并非加重）。当前口径是由理事会人工把关，已作为问题 B 发给协会。
     * 用更轻的第二张来断言，区分不出「没有校验」与「有校验且恰好放行」；用更重的才钉得住。
     * 哪天协会改口要硬拦，本用例会红——那时它就是提醒你连同这段注释一起改的地方。</p>
     *
     * <p>把 V38 的生成列表达式改回 V35 那版，或把 {@code insertWithNewNo} 的预查改回不排除
     * {@code APPEAL_UPHELD}，本用例都必红（前者报数据库撞键，后者报「请勿重复开单」）。</p>
     */
    @Test
    void punish_afterAppealUpheld_canBeReissuedForTheSameViolation() {
        Long vid = insertVolunteer();
        Fixture f = activityWithSlot();
        Long violationId = insertViolation(f, vid);
        violationReviewService.approve(violationId, ADMIN);

        RewardPunishSaveDTO dto = punish(vid, -50);
        dto.setViolationId(violationId);
        Long first = rewardPunishService.create(dto, ADMIN, false);
        approveFully(first, ADMIN);
        rewardPunishService.appeal(first, vid, appealDto());
        AppealHandleDTO handle = new AppealHandleDTO();
        handle.setUpheld(true);
        handle.setResult("原判罚过重，撤销后另行处理");
        rewardPunishService.handleAppeal(first, handle, ADMIN);

        RewardPunishSaveDTO reissue = punish(vid, -100);
        reissue.setViolationId(violationId);
        Long second = rewardPunishService.create(reissue, ADMIN, false);
        assertNotEquals(first, second, "申诉成立之后应当能就同一条违规重新开单");

        // 「同时只能有一张有效单」这条不变量仍在——释放条件多了一种，不是取消了这个键
        assertThrows(BusinessException.class, () -> rewardPunishService.create(reissue, ADMIN, false),
                "第二张还在待审核，不该再开第三张");
    }

    /** 奖励不能附带处置——一张「奖励」单把人限制住，而记录上他是被表扬的。 */
    @Test
    void reward_withSanction_isRejected() {
        Long vid = insertVolunteer();
        RewardPunishSaveDTO dto = reward(vid, 100);
        dto.setSanctionScope(SanctionScope.ACTIVITY);
        assertThrows(BusinessException.class, () -> rewardPunishService.create(dto, ADMIN, false));
    }

    /** 符号写反会让「处罚」给人加分，而列表上仍显示为处罚。 */
    @Test
    void pointsDelta_signMustMatchType() {
        Long vid = insertVolunteer();
        assertThrows(BusinessException.class, () -> rewardPunishService.create(reward(vid, -10), ADMIN, false));
        assertThrows(BusinessException.class, () -> rewardPunishService.create(punish(vid, 10), ADMIN, false));
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
        Long id = rewardPunishService.create(dto, ADMIN, false);

        assertFalse(sanctionQueryService.isRestricted(vid, SanctionScope.ACTIVITY),
                "审核通过前处置不得生效——待审核期间它只是一张草稿");

        approveFully(id, ADMIN);
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
        Long id = rewardPunishService.create(dto, ADMIN, false);
        approveFully(id, ADMIN);
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
        Long id = rewardPunishService.create(dto, ADMIN, false);
        approveFully(id, ADMIN);

        assertTrue(sanctionQueryService.isRestricted(vid, SanctionScope.ACTIVITY));
        assertTrue(sanctionQueryService.isRestricted(vid, SanctionScope.COMMUNITY));

        // 但【不能】挡住奖惩记录与申诉——被罚得最重的人恰恰是最需要申诉的那个
        assertEquals(1, myRecords(vid).size(),
                "拒绝使用本程序不得连奖惩记录一起挡掉，否则他看不到自己被罚了什么");
        assertTrue(myRecords(vid).get(0).getAppealable(),
                "更不能挡掉申诉入口，那会让申诉权形同虚设");
    }

    // ---------- ③ 申诉 ----------

    /** 只有处罚可申诉：P109 的申诉按钮只画在处罚上，奖励卡片只有「查看详情」。 */
    @Test
    void reward_cannotBeAppealed() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(reward(vid, 100), ADMIN, false);
        approveFully(id, ADMIN);

        assertFalse(myRecords(vid).get(0).getAppealable());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> rewardPunishService.appeal(id, vid, appealDto()));
        assertTrue(ex.getMessage().contains("无需申诉"), "实际：" + ex.getMessage());
    }

    /** 申诉期是 7 天：过了截止时刻就不能再申诉。 */
    @Test
    void appeal_afterDeadline_isRejected() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punish(vid, 0), ADMIN, false);
        approveFully(id, ADMIN);

        HonorRewardPunish rp = rewardPunishMapper.selectById(id);
        assertNotNull(rp.getAppealDeadline(), "审核通过时必须把截止时刻写死");
        assertTrue(rp.getAppealDeadline().isAfter(LocalDateTime.now().plusDays(6)), "默认 7 天");

        HonorRewardPunish patch = new HonorRewardPunish();
        patch.setId(id);
        patch.setAppealDeadline(LocalDateTime.now().minusSeconds(1));
        rewardPunishMapper.updateById(patch);

        assertFalse(myRecords(vid).get(0).getAppealable(),
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
        Long id = rewardPunishService.create(punish(vid, 0), ADMIN, false);
        approveFully(id, ADMIN);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> rewardPunishService.appeal(id, intruder, appealDto()));
        assertEquals("奖惩记录不存在", ex.getMessage(), "不得区分「不存在」与「不是你的」");
    }

    /** 重复提交申诉不得覆盖第一次的理由与时间。 */
    @Test
    void appeal_twice_isRejected() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punish(vid, 0), ADMIN, false);
        approveFully(id, ADMIN);
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
        Long id = rewardPunishService.create(dto, ADMIN, false);
        approveFully(id, ADMIN);

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
        Long id = rewardPunishService.create(dto, ADMIN, false);
        approveFully(id, ADMIN);
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
        approveFully(rewardPunishService.create(dto, ADMIN, false), ADMIN);

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
                () -> rewardPunishService.create(reward(999_999_999L, 10), ADMIN, false));
        assertTrue(ex.getMessage().contains("不存在"), "实际：" + ex.getMessage());
    }

    /** 积分幅度必须有界：-Integer.MIN_VALUE 仍是 MIN_VALUE，那张单永远冲正不回来。 */
    @Test
    void create_withUnboundedPoints_isRejected() {
        Long vid = insertVolunteer();
        assertThrows(BusinessException.class,
                () -> rewardPunishService.create(punish(vid, Integer.MIN_VALUE), ADMIN, false));
        assertThrows(BusinessException.class,
                () -> rewardPunishService.create(reward(vid, Integer.MAX_VALUE), ADMIN, false));
    }

    /** 只填天数不填范围 = 一条「有期限但什么也不限制」的单；天数也必须有上限。 */
    @Test
    void create_sanctionDaysWithoutScope_orAbsurdDays_isRejected() {
        Long vid = insertVolunteer();
        RewardPunishSaveDTO noScope = punish(vid, 0);
        noScope.setSanctionDays(7);
        assertThrows(BusinessException.class, () -> rewardPunishService.create(noScope, ADMIN, false));

        RewardPunishSaveDTO tooLong = punish(vid, 0);
        tooLong.setSanctionScope(SanctionScope.ACTIVITY);
        tooLong.setSanctionDays(Integer.MAX_VALUE);
        assertThrows(BusinessException.class, () -> rewardPunishService.create(tooLong, ADMIN, false),
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
        Long id = rewardPunishService.create(punish(vid, -10), ADMIN, false);
        volunteerMapper.deleteById(vid);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> approveFully(id, ADMIN));
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
        Long id = rewardPunishService.create(dto, ADMIN, false);
        Volunteer gone = new Volunteer();
        gone.setId(vid);
        gone.setStatus(UserStatus.DELETED);
        volunteerMapper.updateById(gone);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> approveFully(id, ADMIN));
        assertTrue(ex.getMessage().contains("已注销"), "实际：" + ex.getMessage());
        assertEquals(0, pointService.summary(vid).getBalance(), "不得入账");
        assertFalse(sanctionQueryService.isRestricted(vid, SanctionScope.ACTIVITY), "不得施加处置");
    }

    /**
     * 被<b>禁用</b>（{@code status=1}）的账号：<b>奖励与处罚都照常审核</b>。
     *
     * <p><b>需求出处</b>：协会 2026-08-11 答复第 5 条「账号类处罚：由理事会审核后才生效，
     * <b>只给禁用账号开个小口子、只能看奖惩和提申诉</b>」。</p>
     *
     * <p><b>🔁 这条口径来回改过两次，两次的理由都不互相反驳。</b>第 6 轮评审改成「一律不批」，
     * 因为当时 {@code SaTokenConfigure} 对 {@code status != NORMAL} 的账号拦掉全部 {@code /v/**}，
     * 包括奖惩列表与申诉入口——通过审核等于<b>处罚立刻生效、而 7 天申诉期在他够不到的地方流逝</b>。
     * 协会选的是另一条路：照常批，但把申诉那条路留着。<b>要消除的东西没变，手段变了</b>
     * （{@code ensureLoginable} 给禁用账号发 token，{@code BannedAccountGate.EXEMPT_PATHS} 放行申诉）。</p>
     *
     * <p><b>本用例现在钉的是「不再有那条 if」</b>：把
     * {@code requireApprovableVolunteer} 里的 {@code if (!v.active())} 加回来，本用例必红。
     * 而<b>申诉够不够得到</b>由 {@code BannedAccountGateTest} 从另一头钉住——
     * 这两条用例必须同时活着，只留一条就会退回到某一版的半截状态：
     * 只有本条 = 批得下去但申诉够不到；只有那条 = 口子开着但没有单子会落到禁用账号头上。</p>
     *
     * <p><b>口子只对「禁用」开，不含「注销」</b>——注销那一格由
     * {@link #approve_afterVolunteerDeregistered_isRejected} 钉住，本处不重复。
     * 差别是实质的：禁用的人还在，有处罚要看、有申诉要提；注销的人已经走了。</p>
     */
    @Test
    void approve_afterVolunteerBanned_proceedsForBothRewardAndPunish() {
        Long vid = insertVolunteer();
        Long rewardId = rewardPunishService.create(reward(vid, 20), ADMIN, false);
        RewardPunishSaveDTO punishDto = punish(vid, -10);
        punishDto.setSanctionScope(SanctionScope.ACTIVITY);
        punishDto.setSanctionDays(7);
        Long punishId = rewardPunishService.create(punishDto, ADMIN, false);
        Volunteer banned = new Volunteer();
        banned.setId(vid);
        banned.setStatus(UserStatus.BANNED);
        volunteerMapper.updateById(banned);

        approveFully(rewardId, ADMIN);
        approveFully(punishId, ADMIN);

        assertEquals(10, pointService.summary(vid).getBalance(), "两张单都该入账：+20 奖励、-10 处罚");
        assertTrue(sanctionQueryService.isRestricted(vid, SanctionScope.ACTIVITY), "处置照常施加");

        // 申诉期照常起算，而且他真的够得到——够不到那部分由 BannedAccountGateTest 负责
        HonorRewardPunish approved = rewardPunishMapper.selectById(punishId);
        assertNotNull(approved.getAppealDeadline(), "申诉截止必须落库，它是那条站内提示的内容来源");
    }


    // ---------- ⑤ Row 41 F 的另一半：审核之后志愿者会收到提示 ----------

    /**
     * 审核通过必须给志愿者留下一条站内提示，且<b>处罚那条要写明申诉截止时刻</b>。
     *
     * <p>Row 41 F：「…审核之后，<b>志愿者会收到提示</b>，并有 7 天申诉期」。V32 只做了后半句
     * （截止时刻落库），提示本身一直缺着——志愿者被罚了，只能靠自己去翻奖惩记录才知道，
     * 而 7 天在这期间照走。</p>
     *
     * <p>只说「您可以申诉」而不说到几号，等于把一个有期限的权利说成了没期限的，
     * 故正文直接引用落库的 {@code appeal_deadline}，不另算一遍。</p>
     *
     * <p>删掉 {@code approve} 末尾那次 {@code notificationService.notify}，本用例必红。</p>
     */
    @Test
    void approve_leavesNotificationWithAppealDeadline() {
        Long vid = insertVolunteer();
        Long punishId = rewardPunishService.create(punish(vid, -5), ADMIN, false);
        assertEquals(0, notificationService.unreadCount(vid), "审核前不该有任何提示");

        approveFully(punishId, ADMIN);

        // 断言 records 而不是 total：分页插件只注册在 api 模块，honor 的测试上下文没有它，
        // 无插件时 selectPage 照常返回记录、total 恒为 0（详见 NotificationServiceTest 抬头）
        var page = notificationService.myNotifications(vid, new PageQuery());
        assertEquals(1, page.getRecords().size());
        VolunteerNotification n = page.getRecords().get(0);
        assertEquals(VolunteerNotification.TYPE_REWARD_PUNISH_APPROVED, n.getType());
        assertEquals(VolunteerNotification.BIZ_REWARD_PUNISH, n.getBizType());
        assertEquals(punishId, n.getBizId(), "要能跳回那张单");
        assertTrue(n.getTitle().contains("处罚"), "实际：" + n.getTitle());

        HonorRewardPunish rp = rewardPunishMapper.selectById(punishId);
        String deadline = rp.getAppealDeadline()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        assertTrue(n.getContent().contains(deadline),
                "处罚提示必须写明申诉截止到几号，实际：" + n.getContent());
    }

    /**
     * 上一条用例为什么会偶发变红——以及 {@code approve} 里那句 {@code truncatedTo(SECONDS)} 是为谁写的。
     *
     * <p><b>MySQL 对 {@code DATETIME(fsp=0)} 的小数秒是「四舍五入」而不是「截断」</b>
     * （5.6.4 起改的口径）。审核恰好发生在某一分钟的第 59.5 秒之后时，
     * 落库的 {@code appeal_deadline} 会被进位到下一分钟，而站内提示是拿<b>内存里</b>那个值
     * 格式化出来的——于是<b>告知志愿者的截止时刻比实际执行的早一分钟</b>。
     * 申诉期是对志愿者的承诺，这两个数字必须是同一个。</p>
     *
     * <p>发生概率约 0.8%（每分钟末尾 0.5 秒），所以它以「上一条用例偶尔红一次」的形式存在了很久，
     * 而随手重跑一次就绿了。本用例把那个机制<b>确定性地</b>钉下来：不依赖运行时刻。</p>
     *
     * <p>把 {@code approve} 里的 {@code truncatedTo(ChronoUnit.SECONDS)} 去掉，
     * 上一条用例会恢复成偶发红；本条仍然绿——它证明的是数据库行为，不是我们的代码。
     * 两条合起来才说明白：<b>库会进位，所以我们必须先截。</b></p>
     */
    @Test
    void mysqlRoundsFractionalSecondsUp_whichIsWhyApproveTruncatesToSeconds() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punish(vid, -5), ADMIN, false);

        HonorRewardPunish patch = new HonorRewardPunish();
        patch.setId(id);
        patch.setAppealDeadline(LocalDateTime.of(2026, 8, 18, 16, 35, 59, 700_000_000));
        rewardPunishMapper.updateById(patch);

        assertEquals(LocalDateTime.of(2026, 8, 18, 16, 36, 0),
                rewardPunishMapper.selectById(id).getAppealDeadline(),
                "MySQL 把 16:35:59.7 进位成了 16:36:00——不是截断成 16:35:59");
    }

    /** 奖励也提示，但不提申诉——P109 的奖励卡片只有「查看详情」，没有申诉按钮。 */
    @Test
    void approve_rewardNotification_doesNotMentionAppeal() {
        Long vid = insertVolunteer();
        Long rewardId = rewardPunishService.create(reward(vid, 20), ADMIN, false);

        approveFully(rewardId, ADMIN);

        VolunteerNotification n = notificationService.myNotifications(vid, new PageQuery())
                .getRecords().get(0);
        assertTrue(n.getTitle().contains("奖励"), "实际：" + n.getTitle());
        assertFalse(n.getContent().contains("申诉"), "奖励无需申诉，实际：" + n.getContent());
    }

    /**
     * 驳回的单<b>不提示</b>——志愿者始终看不到这张单，凭空收到一条「您的某处罚被驳回」
     * 等于把他本来不该知道的草稿告诉了他。
     */
    @Test
    void reject_leavesNoNotification() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punish(vid, -5), ADMIN, false);

        rewardPunishService.reject(id, "证据不足", ADMIN);

        assertEquals(0, notificationService.unreadCount(vid));
    }

    /**
     * 申诉受理有了结果也提示，<b>成立与驳回都发</b>。
     *
     * <p>⚠️ 这一条是<b>推论</b>（见 {@code VolunteerNotification.TYPE_APPEAL_HANDLED}）：
     * Row 41 F 没写申诉结果要不要提示。只在成立时发则等于用「有没有收到提示」泄露结论，
     * 而驳回恰恰是更需要把理由送到他眼前的那一种，故正文带上受理说明。</p>
     */
    @Test
    void handleAppeal_notifiesOnBothOutcomes() {
        Long upheldId = appealedPunish();
        Long upheldVid = rewardPunishMapper.selectById(upheldId).getVolunteerId();
        AppealHandleDTO uphold = new AppealHandleDTO();
        uphold.setUpheld(true);
        uphold.setResult("经核实确为接家长电话");
        rewardPunishService.handleAppeal(upheldId, uphold, ADMIN);

        VolunteerNotification ok = latestNotification(upheldVid);
        assertEquals(VolunteerNotification.TYPE_APPEAL_HANDLED, ok.getType());
        assertTrue(ok.getTitle().contains("成立"), "实际：" + ok.getTitle());
        assertTrue(ok.getContent().contains("经核实确为接家长电话"),
                "受理说明必须原样带给志愿者，实际：" + ok.getContent());

        Long rejectedId = appealedPunish();
        Long rejectedVid = rewardPunishMapper.selectById(rejectedId).getVolunteerId();
        AppealHandleDTO reject = new AppealHandleDTO();
        reject.setUpheld(false);
        reject.setResult("现场有多人可证");
        rewardPunishService.handleAppeal(rejectedId, reject, ADMIN);

        VolunteerNotification no = latestNotification(rejectedVid);
        assertEquals(VolunteerNotification.TYPE_APPEAL_HANDLED, no.getType());
        assertTrue(no.getContent().contains("现场有多人可证"),
                "驳回更需要把理由送到他眼前，实际：" + no.getContent());
    }

    /** 开一张已通过审核并已提交申诉的处罚单，返回单据 id。 */
    // ---------- 两级审核的测试助手（协会 2026-09-02 答复问题二） ----------

    /**
     * 把一张单一路推到「已通过」。
     *
     * <p>改造前 {@code approve} 一步到位，本类几十处用例都建立在那之上。改造后它成了两步，
     * 但那些用例断言的是<b>「通过之后应当怎样」</b>——积分入账、处置生效、提示送达——
     * 这些性质没有改变，只是发生的时刻从初审挪到了终审。故用本助手把两步合起来，
     * 让原有断言继续覆盖它们原本要覆盖的东西。</p>
     *
     * <p><b>两级审核本身由 {@link RewardPunishTwoStageTest} 单独钉住</b>，不靠这些用例——
     * 它们要是能顺带证明两级，说明本助手写得不对。</p>
     *
     * <p>按当前状态决定要不要先初审：处罚从 0 起步，奖励开单即落 3（不经组织部）。</p>
     */
    private void approveFully(Long id, Long adminId) {
        HonorRewardPunish rp = rewardPunishMapper.selectById(id);
        if (rp != null && Integer.valueOf(HonorRewardPunish.REVIEW_PENDING).equals(rp.getReviewStatus())) {
            rewardPunishService.firstApprove(id, adminId);
        }
        rewardPunishService.finalApprove(id, adminId);
    }

    private Long appealedPunish() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punish(vid, -5), ADMIN, false);
        approveFully(id, ADMIN);
        rewardPunishService.appeal(id, vid, appealDto());
        return id;
    }

    private VolunteerNotification latestNotification(Long vid) {
        return notificationService.myNotifications(vid, new PageQuery()).getRecords().get(0);
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
                () -> rewardPunishService.create(punish(vid, -10), ADMIN, false));
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
        Long draft = rewardPunishService.create(punish(vid, 0), ADMIN, false);
        Long appealed = rewardPunishService.create(punish(vid, 0), ADMIN, false);
        approveFully(appealed, ADMIN);
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

    /**
     * 志愿者端「我的奖惩」。
     *
     * <p>V43 起该方法返回 {@link com.hengde.common.page.PageResult}，这里取 records 断言。
     * <b>刻意不断言 total</b>：分页拦截器只装配在 api 模块，领域模块的测试上下文里
     * {@code selectPage} 不加 LIMIT、total 恒为 0——断言 total 会得到一条
     * 「在测试里永远成立、在生产里毫无意义」的用例。</p>
     */
    private List<RewardPunishVO> myRecords(Long vid) {
        return rewardPunishService.myRecords(vid, new PageQuery()).getRecords();
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

    // ---------- V45：申诉凭证图片 ----------

    /** 传了凭证要能原样读回，顺序不变。 */
    @Test
    void appealImages_roundTrip() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punish(vid, 0), ADMIN, false);
        approveFully(id, ADMIN);

        AppealSubmitDTO dto = appealDto();
        dto.setImageUrls(List.of("https://oss/a.jpg", "https://oss/b.jpg"));
        rewardPunishService.appeal(id, vid, dto);

        List<String> got = myRecords(vid).get(0).getAppealImageUrls();
        assertEquals(List.of("https://oss/a.jpg", "https://oss/b.jpg"), got, "顺序也要保持");
    }

    /** 没传凭证时回<b>空数组而不是 null</b>——客户端少一处判空。 */
    @Test
    void appealImages_absentIsEmptyListNotNull() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punish(vid, 0), ADMIN, false);
        approveFully(id, ADMIN);
        rewardPunishService.appeal(id, vid, appealDto());

        List<String> got = myRecords(vid).get(0).getAppealImageUrls();
        assertNotNull(got, "空要给空数组，不要给 null");
        assertTrue(got.isEmpty());
    }

    /**
     * 超过 6 张当场拒绝，<b>不靠列宽兜底</b>。
     *
     * <p>靠列宽的话，非严格模式会静默截断——申诉提交成功了，受理人看到的却是
     * 一张打不开的半截 URL；严格模式抛的错指向列名，同样不会告诉用户「最多 6 张」。</p>
     */
    @Test
    void appealImages_overLimit_isRejectedWithUsefulMessage() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punish(vid, 0), ADMIN, false);
        approveFully(id, ADMIN);

        AppealSubmitDTO dto = appealDto();
        dto.setImageUrls(List.of("https://o/1.jpg", "https://o/2.jpg", "https://o/3.jpg",
                "https://o/4.jpg", "https://o/5.jpg", "https://o/6.jpg", "https://o/7.jpg"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> rewardPunishService.appeal(id, vid, dto));
        assertTrue(ex.getMessage().contains("最多上传 6 张"), "实际：" + ex.getMessage());

        // 被拒之后申诉不该算已提交，否则他再也提不了了
        assertEquals(0, rewardPunishMapper.selectById(id).getAppealStatus(),
                "校验失败不该把单子推进「申诉中」");
    }

    /**
     * URL 里混进逗号当场拒绝——逗号是分隔符本身。
     *
     * <p>放过去的话存进去时看着好好的，读出来会被切成两条坏链接，
     * 是「存的时候没事、看的时候才坏」那一类。</p>
     */
    @Test
    void appealImages_urlWithComma_isRejected() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punish(vid, 0), ADMIN, false);
        approveFully(id, ADMIN);

        AppealSubmitDTO dto = appealDto();
        dto.setImageUrls(List.of("https://oss/a,b.jpg"));
        assertThrows(BusinessException.class, () -> rewardPunishService.appeal(id, vid, dto));
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
