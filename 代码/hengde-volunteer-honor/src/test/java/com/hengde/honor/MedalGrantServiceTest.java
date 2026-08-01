package com.hengde.honor;

import com.hengde.activity.constant.ActivityStatus;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.constant.SecretaryStatus;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.service.PointService;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.honor.constant.MedalConditionType;
import com.hengde.honor.constant.MedalGrantStatus;
import com.hengde.honor.dto.MedalGrantDTO;
import com.hengde.honor.dto.MedalSaveDTO;
import com.hengde.honor.service.MedalGrantService;
import com.hengde.honor.service.MedalService;
import com.hengde.honor.vo.MedalGrantVO;
import com.hengde.honor.vo.MyMedalVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 勋章<b>发放审核</b>（第二重审核）+ 积分入账 + 获取进度验证。<b>需本机 Docker</b>（MySQL + Redis）。
 *
 * <p><b>断言一律按 medalId 过滤而非按列表长度</b>——{@code myMedals} 返回的是
 * 「已启用勋章 ∪ 本人已获得的勋章」，别的用例造的勋章也会出现在里面，按长度断言会随用例增减而失效。</p>
 *
 * <p>样式一律按 <b>V29 的最后过审快照</b>展示，见 {@code myMedals_showsLastApprovedStyle_notPendingEdit}。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class MedalGrantServiceTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final Long AUDITOR = 9002L;
    private static final Long APPLICANT = 9003L;

    private MedalService medalService;
    private MedalGrantService medalGrantService;
    private PointService pointService;
    private VolunteerMapper volunteerMapper;
    private ActivityMapper activityMapper;
    @Autowired
    private ActivitySlotMapper slotMapper;
    private ActivityAttendanceMapper attendanceMapper;

    @Autowired
    public void setMedalService(MedalService medalService) {
        this.medalService = medalService;
    }

    @Autowired
    public void setMedalGrantService(MedalGrantService medalGrantService) {
        this.medalGrantService = medalGrantService;
    }

    @Autowired
    public void setPointService(PointService pointService) {
        this.pointService = pointService;
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

    // ================= 样式审核这一重的实际作用 =================

    /** 未过样式审核的勋章不得发放——否则「样式须先过审」只是句口号。 */
    @Test
    void grant_rejectedWhenMedalNotApproved() {
        Long draftMedal = medalService.create(medalDto("未过审", 0));
        Long volunteer = volunteer("小明");

        BusinessException e = assertThrows(BusinessException.class,
                () -> medalGrantService.apply(grantDto(draftMedal, volunteer), APPLICANT));
        assertTrue(e.getMessage().contains("样式审核") || e.getMessage().contains("停用"),
                "应明确提示样式未过审：" + e.getMessage());
    }

    @Test
    void grant_rejectedWhenMedalDisabled() {
        Long medal = enabledMedal("停用后不可发", 0);
        medalService.disable(medal);

        assertThrows(BusinessException.class,
                () -> medalGrantService.apply(grantDto(medal, volunteer("小红")), APPLICANT));
    }

    @Test
    void grant_rejectedForUnregisteredVolunteer() {
        Long medal = enabledMedal("游客不可授", 0);
        assertThrows(BusinessException.class,
                () -> medalGrantService.apply(grantDto(medal, guest()), APPLICANT),
                "未实名的游客不该被授予勋章");
    }

    // ================= 发放审核这一重 =================

    /** 待审核的发放<b>志愿者端不可见</b>，否则审核形同虚设。 */
    @Test
    void pendingGrant_isInvisibleToVolunteer() {
        Long medal = enabledMedal("待审不可见", 0);
        Long volunteer = volunteer("待审小张");
        medalGrantService.apply(grantDto(medal, volunteer), APPLICANT);

        assertFalse(ownedOf(volunteer, medal), "发放尚未审核通过，志愿者不该看到已获得");
    }

    @Test
    void approve_makesGrantVisibleToVolunteer() {
        Long medal = enabledMedal("通过可见", 0);
        Long volunteer = volunteer("通过小李");
        Long grantId = medalGrantService.apply(grantDto(medal, volunteer), APPLICANT);

        medalGrantService.approve(grantId, AUDITOR);

        assertTrue(ownedOf(volunteer, medal));
        MyMedalVO mine = myMedal(volunteer, medal);
        assertNotNull(mine.getGrantTime(), "生效后应有获得时间");
    }

    @Test
    void reject_doesNotTakeEffect_andAllowsReapply() {
        Long medal = enabledMedal("驳回可重发", 0);
        Long volunteer = volunteer("驳回小王");
        Long first = medalGrantService.apply(grantDto(medal, volunteer), APPLICANT);

        medalGrantService.reject(first, "证据不足", AUDITOR);
        assertFalse(ownedOf(volunteer, medal), "驳回不生效");

        // 关键：驳回的记录不占唯一键，同一人可以重新发起——用死约束会把人永久挡在门外
        Long second = medalGrantService.apply(grantDto(medal, volunteer), APPLICANT);
        medalGrantService.approve(second, AUDITOR);
        assertTrue(ownedOf(volunteer, medal), "驳回后重新发起并通过应当生效");
    }

    @Test
    void duplicateGrant_isRejectedWhilePendingAndAfterEffective() {
        Long medal = enabledMedal("防重复", 0);
        Long volunteer = volunteer("重复小赵");
        Long grantId = medalGrantService.apply(grantDto(medal, volunteer), APPLICANT);

        assertThrows(BusinessException.class,
                () -> medalGrantService.apply(grantDto(medal, volunteer), APPLICANT),
                "已有待审核记录时不该重复发起");

        medalGrantService.approve(grantId, AUDITOR);
        assertThrows(BusinessException.class,
                () -> medalGrantService.apply(grantDto(medal, volunteer), APPLICANT),
                "已获得后不该重复授予");
    }

    @Test
    void approveGrant_isCasProtected() {
        Long medal = enabledMedal("发放CAS", 0);
        Long grantId = medalGrantService.apply(grantDto(medal, volunteer("CAS小孙")), APPLICANT);
        medalGrantService.approve(grantId, AUDITOR);

        assertThrows(BusinessException.class, () -> medalGrantService.approve(grantId, AUDITOR));
    }

    @Test
    void approve_rejectedWhenMedalDisabledAfterApply() {
        Long medal = enabledMedal("发起后停用", 0);
        Long grantId = medalGrantService.apply(grantDto(medal, volunteer("停用小周")), APPLICANT);
        medalService.disable(medal);

        assertThrows(BusinessException.class, () -> medalGrantService.approve(grantId, AUDITOR),
                "发起之后勋章被停用，不该还能让它生效");
    }

    @Test
    void auditorAndApplicantMustNotBeNull() {
        Long medal = enabledMedal("操作人非空", 0);
        assertThrows(BusinessException.class,
                () -> medalGrantService.apply(grantDto(medal, volunteer("空发起人")), null));
        Long grantId = medalGrantService.apply(grantDto(medal, volunteer("空审核人")), APPLICANT);
        assertThrows(BusinessException.class, () -> medalGrantService.approve(grantId, null));
        assertThrows(BusinessException.class, () -> medalGrantService.reject(grantId, "x", null));
    }

    // ================= 附带积分 =================

    /** 积分只在<b>生效那一刻</b>入账；未审核就发分等于绕过审核给了实际权益。 */
    @Test
    void rewardPoints_landOnlyAfterApproval() {
        Long medal = enabledMedal("带积分", 30);
        Long volunteer = volunteer("积分小吴");
        int before = pointService.balanceOf(volunteer);

        Long grantId = medalGrantService.apply(grantDto(medal, volunteer), APPLICANT);
        assertEquals(before, pointService.balanceOf(volunteer), "待审核时不该发分");

        medalGrantService.approve(grantId, AUDITOR);

        assertEquals(before + 30, pointService.balanceOf(volunteer), "生效后应入账 30 分");
    }

    @Test
    void rewardPointsZero_writesNoLedgerRow() {
        Long medal = enabledMedal("零积分", 0);
        Long volunteer = volunteer("零分小郑");
        int before = pointService.balanceOf(volunteer);

        medalGrantService.approve(
                medalGrantService.apply(grantDto(medal, volunteer), APPLICANT), AUDITOR);

        assertEquals(before, pointService.balanceOf(volunteer), "0 分不入账，账本只记真实变动");
    }

    @Test
    void rejectedGrant_awardsNothing() {
        Long medal = enabledMedal("驳回不发分", 50);
        Long volunteer = volunteer("驳回小冯");
        int before = pointService.balanceOf(volunteer);

        medalGrantService.reject(
                medalGrantService.apply(grantDto(medal, volunteer), APPLICANT), "不符合条件", AUDITOR);

        assertEquals(before, pointService.balanceOf(volunteer));
    }

    /**
     * 积分奖励按<b>发起时的快照</b>发放，不按审核时的定义现读。
     *
     * <p>否则申请与审核之间管理员改了 {@code rewardPoints}，审核人批准的就不再是他看到的那个数。</p>
     */
    @Test
    void rewardPoints_useSnapshotTakenAtApplyTime() {
        Long medal = enabledMedal("快照积分", 20);
        Long volunteer = volunteer("快照小陈");
        int before = pointService.balanceOf(volunteer);
        Long grantId = medalGrantService.apply(grantDto(medal, volunteer), APPLICANT);

        // 发起之后把奖励改成 200，并重新过审使其可发放
        MedalSaveDTO changed = medalDto("快照积分改后", 200);
        medalService.update(medal, changed);
        medalService.approve(medal, AUDITOR);

        medalGrantService.approve(grantId, AUDITOR);

        assertEquals(before + 20, pointService.balanceOf(volunteer),
                "应按发起时快照的 20 分发放，而不是审核时定义里的 200 分");
    }

    // ================= 获取进度 =================

    @Test
    void progress_forServiceMinutes() {
        Long volunteer = volunteer("时长进度");
        attend(volunteer, 90, SecretaryStatus.CONFIRMED);
        Long medal = enabledConditionMedal("满180分钟", MedalConditionType.SERVICE_MINUTES, 180L);

        MyMedalVO vo = myMedal(volunteer, medal);
        assertEquals(90L, vo.getCurrentValue());
        assertEquals(50, vo.getProgressPercent());
        assertFalse(vo.getOwned());
    }

    @Test
    void progress_forActivityCount() {
        Long volunteer = volunteer("次数进度");
        attend(volunteer, 60, SecretaryStatus.CONFIRMED);
        attend(volunteer, 60, SecretaryStatus.CONFIRMED);
        Long medal = enabledConditionMedal("满4次", MedalConditionType.ACTIVITY_COUNT, 4L);

        MyMedalVO vo = myMedal(volunteer, medal);
        assertEquals(2L, vo.getCurrentValue());
        assertEquals(50, vo.getProgressPercent());
    }

    /**
     * 积分进度用<b>累计获得</b>而非余额。
     *
     * <p>与排行榜、积分中心同一口径：花掉积分不该让人丢掉已经挣到的勋章进度。</p>
     */
    @Test
    void progress_forPoints_usesEarnedNotBalance() {
        Long volunteer = volunteer("积分进度");
        pointService.record(volunteer, 100, PointSourceType.ACTIVITY, uniqueSourceId(),
                "测试入账", PointSourceType.OPERATOR_SYSTEM, null);
        pointService.record(volunteer, -60, PointSourceType.EXCHANGE, uniqueSourceId(),
                "测试消费", PointSourceType.OPERATOR_SYSTEM, null);
        Long medal = enabledConditionMedal("满200分", MedalConditionType.EARNED_POINTS, 200L);

        MyMedalVO vo = myMedal(volunteer, medal);
        assertEquals(100L, vo.getCurrentValue(), "余额是 40，累计获得是 100——进度看后者");
        assertEquals(50, vo.getProgressPercent());
    }

    @Test
    void progress_isCappedAtHundred() {
        Long volunteer = volunteer("超额进度");
        attend(volunteer, 600, SecretaryStatus.CONFIRMED);
        Long medal = enabledConditionMedal("满60分钟", MedalConditionType.SERVICE_MINUTES, 60L);

        assertEquals(100, myMedal(volunteer, medal).getProgressPercent(),
                "超额完成显示 1000% 只会让人困惑");
    }

    @Test
    void progress_absentForManualMedals() {
        Long volunteer = volunteer("手动无进度");
        Long medal = enabledMedal("手动授予", 0);

        MyMedalVO vo = myMedal(volunteer, medal);
        assertNull(vo.getCurrentValue(), "手动授予类没有进度");
        assertNull(vo.getProgressPercent());
    }

    @Test
    void progress_ignoresUnconfirmedMinutes() {
        Long volunteer = volunteer("未确认时长");
        attend(volunteer, 500, SecretaryStatus.PENDING);
        Long medal = enabledConditionMedal("满100分钟", MedalConditionType.SERVICE_MINUTES, 100L);

        assertEquals(0L, myMedal(volunteer, medal).getCurrentValue(),
                "未经秘书部确认的时长不算进度，与服务记录口径一致");
    }

    /**
     * 草稿 / 已取消 / 待审核发布等<b>非真实活动</b>上的考勤不该顶起勋章进度。
     *
     * <p>排行榜与数据看板都只认「已发布 / 已结束」的活动，勋章进度必须同口径，
     * 否则同一个人在排行榜上是 0 次、在勋章进度里却已经快满了。</p>
     */
    @Test
    void progress_ignoresAttendanceOnNonRealActivities() {
        Long volunteer = volunteer("脏活动考勤");
        attendOn(volunteer, ActivityStatus.DRAFT, 300, SecretaryStatus.CONFIRMED, true);
        attendOn(volunteer, ActivityStatus.CANCELLED, 300, SecretaryStatus.CONFIRMED, true);
        attendOn(volunteer, ActivityStatus.PENDING_REVIEW, 300, SecretaryStatus.CONFIRMED, true);

        Long minutesMedal = enabledConditionMedal("脏时长", MedalConditionType.SERVICE_MINUTES, 100L);
        Long countMedal = enabledConditionMedal("脏次数", MedalConditionType.ACTIVITY_COUNT, 2L);

        assertEquals(0L, myMedal(volunteer, minutesMedal).getCurrentValue(),
                "草稿/已取消/待审核活动上的时长不算进度");
        assertEquals(0L, myMedal(volunteer, countMedal).getCurrentValue(),
                "同上，次数也不算");
    }

    /** 没有签到时间的考勤行不算「参加过」——与排行榜一致（否则它在总榜有、在月榜没有，两边加不起来）。 */
    @Test
    void progress_ignoresAttendanceWithoutCheckIn() {
        Long volunteer = volunteer("无签到考勤");
        attendOn(volunteer, ActivityStatus.PUBLISHED, 300, SecretaryStatus.CONFIRMED, false);

        Long countMedal = enabledConditionMedal("无签到次数", MedalConditionType.ACTIVITY_COUNT, 2L);
        assertEquals(0L, myMedal(volunteer, countMedal).getCurrentValue(),
                "考勤行没有签到时间就不算参加过");
    }

    // ================= 停用与「我的勋章」 =================

    /**
     * 样式停用后，<b>已经生效的发放不该从志愿者端消失</b>。
     *
     * <p>停用的语义是「不再发新的」而不是「收回已发的」——{@code MedalStatus} 与接口文档都是这么承诺的。
     * 早前实现只遍历「已启用」定义，一停用志愿者手上的勋章就凭空不见了，与承诺相反。</p>
     */
    @Test
    void myMedals_keepsOwnedMedalAfterDefinitionDisabled() {
        Long medal = enabledMedal("停用不收回", 0);
        Long owner = volunteer("持有小徐");
        medalGrantService.approve(medalGrantService.apply(grantDto(medal, owner), APPLICANT), AUDITOR);

        medalService.disable(medal);

        assertTrue(ownedOf(owner, medal), "停用样式不该让已获得的勋章消失");
        // 反向：没获得的人不该因为这次并集而看到一枚已停用的勋章
        Long bystander = volunteer("旁人小许");
        assertTrue(medalGrantService.myMedals(bystander).stream()
                        .noneMatch(v -> medal.equals(v.getMedalId())),
                "已停用的勋章只对持有者可见，不该出现在其他人的列表里");
    }

    // ================= 后台列表 =================

    @Test
    void listGrants_carriesMedalAndVolunteerName() {
        Long medal = enabledMedal("列表带名", 0);
        Long volunteer = volunteer("列表小钱");
        Long grantId = medalGrantService.apply(grantDto(medal, volunteer), APPLICANT);

        MedalGrantVO vo = medalGrantService.listGrants(MedalGrantStatus.PENDING, volunteer).stream()
                .filter(g -> grantId.equals(g.getId())).findFirst().orElseThrow();
        assertNotNull(vo.getMedalName());
        assertEquals("列表小钱", vo.getVolunteerName());
        assertEquals("待审核", vo.getStatusLabel());
    }

    // ================= 已获得者只看「最后过审版本」（V29） =================

    /**
     * 改一枚<b>已经发出去</b>的勋章后，已获得者仍应看到<b>上一次过审</b>的样式，而不是待审的新样式。
     *
     * <p>缺陷形态：{@code update} 原地覆盖 name/icon/description 并把状态退回待审核；
     * {@code myMedals} 的「已启用 ∪ 本人已获得」又按 medalId 把这一行补了回来。
     * 于是已获得者立刻看到管理员刚改、<b>尚未过审</b>的内容——「样式必须审核」在他们身上失效；
     * 这一版即便随后被驳回，驳回稿也会一直挂着，因为它就是当前行。</p>
     *
     * <p>修复是 V29 的 {@code approved_*} 快照：审核通过时把当前定义刷进快照，志愿者端只读快照。</p>
     */
    @Test
    void myMedals_showsLastApprovedStyle_notPendingEdit() {
        Long medalId = enabledMedal("过审样式", 0);
        Long volunteerId = volunteer("看样式的小周");
        Long grantId = medalGrantService.apply(grantDto(medalId, volunteerId), APPLICANT);
        medalGrantService.approve(grantId, AUDITOR);

        String approvedName = myMedal(volunteerId, medalId).getName();
        assertEquals("https://example.com/medal.png", myMedal(volunteerId, medalId).getIconUrl());

        // 管理员改成明显不同的一版：状态退回待审核
        MedalSaveDTO changed = medalDto("未过审样式", 0);
        changed.setIconUrl("https://example.com/UNREVIEWED.png");
        changed.setDescription("这段说明还没过审");
        medalService.update(medalId, changed);

        MyMedalVO duringReview = myMedal(volunteerId, medalId);
        assertEquals(approvedName, duringReview.getName(), "待审期间已获得者应仍看到过审版本的名称");
        assertEquals("https://example.com/medal.png", duringReview.getIconUrl(),
                "待审的新图标不该外泄给已获得者");
        /* 关键的一条：过审那版**没有说明**（medalDto 不设 description），快照里
           approved_description 就是 NULL。若按字段逐个判空回退，这里会取到当前的待审说明。
           必须断言仍为 null——「上一版就是没有说明」本身就是快照内容。 */
        assertNull(duringReview.getDescription(),
                "上一版过审时说明为空，待审期间就该仍为空；逐字段判空回退会把未过审的说明泄露出去");
        assertTrue(Boolean.TRUE.equals(duringReview.getOwned()), "已获得的事实不受样式重审影响");

        // 驳回之后仍然是过审版本，而不是驳回稿
        medalService.reject(medalId, "图标不合规", AUDITOR);
        MyMedalVO afterReject = myMedal(volunteerId, medalId);
        assertEquals(approvedName, afterReject.getName(), "驳回稿不该展示给已获得者");
        assertEquals("https://example.com/medal.png", afterReject.getIconUrl());
        assertNull(afterReject.getDescription(), "驳回稿的说明同样不该展示");

        // 重新提交并通过后，快照才更新，所有已获得者同步看到新样式
        medalService.submit(medalId);
        medalService.approve(medalId, AUDITOR);
        MyMedalVO afterApprove = myMedal(volunteerId, medalId);
        assertEquals(changed.getName(), afterApprove.getName(), "过审后快照应更新");
        assertEquals("https://example.com/UNREVIEWED.png", afterApprove.getIconUrl());
        assertEquals("这段说明还没过审", afterApprove.getDescription(),
                "过审之后这段说明才允许出现——此时它已经不是「未过审内容」了");
    }

    // ================= 审核生效时复核志愿者资格 =================

    /**
     * 发起之后志愿者被禁用，本次发放审核必须失败，且<b>不得入账积分</b>。
     *
     * <p>缺陷形态：{@code apply} 校验了实名与账号状态，但 {@code approve} 只复核勋章。
     * 权益（勋章生效 + 积分）产生在审核那一刻，而发起与审核之间往往隔着好几天，
     * 期间志愿者被禁用/注销/删除，待审发放照样能通过并发分。</p>
     */
    @Test
    void approveGrant_rejectsWhenVolunteerBannedAfterApply() {
        Long medalId = enabledMedal("发起后被禁用", 30);
        Long volunteerId = volunteer("被禁用的小吴");
        Long grantId = medalGrantService.apply(grantDto(medalId, volunteerId), APPLICANT);

        Volunteer banned = new Volunteer();
        banned.setId(volunteerId);
        banned.setStatus(1);
        volunteerMapper.updateById(banned);

        assertThrows(BusinessException.class, () -> medalGrantService.approve(grantId, AUDITOR),
                "志愿者已被禁用，这次发放不该还能生效");

        assertEquals(MedalGrantStatus.PENDING,
                medalGrantService.listGrants(null, volunteerId).get(0).getStatus(),
                "发放应仍停在待审核，等账号恢复或由管理员驳回");
        assertEquals(0, pointService.summary(volunteerId).getTotalEarned(), "未生效就不该发分");
    }

    /** 志愿者被注销（status=2）同样不得通过。 */
    @Test
    void approveGrant_rejectsWhenVolunteerDeactivatedAfterApply() {
        Long medalId = enabledMedal("发起后注销", 30);
        Long volunteerId = volunteer("注销的小郑");
        Long grantId = medalGrantService.apply(grantDto(medalId, volunteerId), APPLICANT);

        Volunteer deactivated = new Volunteer();
        deactivated.setId(volunteerId);
        deactivated.setStatus(2);
        volunteerMapper.updateById(deactivated);

        assertThrows(BusinessException.class, () -> medalGrantService.approve(grantId, AUDITOR),
                "志愿者已注销，这次发放不该还能生效");
        assertEquals(0, pointService.summary(volunteerId).getTotalEarned());
    }

    // ---------- helpers ----------

    private boolean ownedOf(Long volunteerId, Long medalId) {
        return Boolean.TRUE.equals(myMedal(volunteerId, medalId).getOwned());
    }

    private MyMedalVO myMedal(Long volunteerId, Long medalId) {
        return medalGrantService.myMedals(volunteerId).stream()
                .filter(v -> medalId.equals(v.getMedalId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("已启用勋章应出现在志愿者端列表：" + medalId));
    }

    private Long enabledMedal(String name, int rewardPoints) {
        Long id = medalService.create(medalDto(name, rewardPoints));
        medalService.submit(id);
        medalService.approve(id, AUDITOR);
        return id;
    }

    private Long enabledConditionMedal(String name, int conditionType, long threshold) {
        MedalSaveDTO dto = medalDto(name, 0);
        dto.setConditionType(conditionType);
        dto.setConditionThreshold(threshold);
        Long id = medalService.create(dto);
        medalService.submit(id);
        medalService.approve(id, AUDITOR);
        return id;
    }

    private MedalSaveDTO medalDto(String name, int rewardPoints) {
        MedalSaveDTO dto = new MedalSaveDTO();
        dto.setName(name + "_" + SEQ.incrementAndGet());
        dto.setIconUrl("https://example.com/medal.png");
        dto.setConditionType(MedalConditionType.MANUAL);
        dto.setRewardPoints(rewardPoints);
        dto.setSort(0);
        return dto;
    }

    private MedalGrantDTO grantDto(Long medalId, Long volunteerId) {
        MedalGrantDTO dto = new MedalGrantDTO();
        dto.setMedalId(medalId);
        dto.setVolunteerId(volunteerId);
        dto.setReason("表现优异");
        return dto;
    }

    private Long volunteer(String realName) {
        Volunteer v = new Volunteer();
        v.setOpenid("medal:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setStatus(0);
        v.setRealName(realName);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private Long guest() {
        Volunteer v = new Volunteer();
        v.setOpenid("medal:guest:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setStatus(0);
        volunteerMapper.insert(v);
        return v.getId();
    }

    private void attend(Long volunteerId, int minutes, int secretaryStatus) {
        attendOn(volunteerId, ActivityStatus.PUBLISHED, minutes, secretaryStatus, true);
    }

    /**
     * 造一条考勤，可指定所属活动的发布状态与是否签到——用于验证进度只认
     * 「已发布/已结束活动 + 有签到时间」的行。
     */
    private void attendOn(Long volunteerId, int activityStatus, int minutes, int secretaryStatus,
                          boolean checkedIn) {
        Activity a = new Activity();
        a.setTitle("勋章测试活动_" + System.nanoTime() + "_" + SEQ.incrementAndGet());
        a.setStartTime(LocalDateTime.now().minusDays(1));
        a.setEndTime(LocalDateTime.now().minusDays(1).plusHours(2));
        a.setStatus(activityStatus);
        activityMapper.insert(a);

        // V30：考勤下沉到场次，slot_id NOT NULL，故须先造一个真实场次
        ActivitySlot slot = new ActivitySlot();
        slot.setActivityId(a.getId());
        slot.setProjectName("岗位_" + System.nanoTime() + "_" + SEQ.incrementAndGet());
        slot.setStartTime(a.getStartTime());
        slot.setEndTime(a.getEndTime());
        slot.setNeedCount(10);
        slotMapper.insert(slot);

        ActivityAttendance att = new ActivityAttendance();
        att.setActivityId(a.getId());
        att.setSlotId(slot.getId());
        att.setVolunteerId(volunteerId);
        att.setCheckInTime(checkedIn ? LocalDateTime.now().minusDays(1) : null);
        att.setServiceMinutes(minutes);
        att.setSecretaryStatus(secretaryStatus);
        attendanceMapper.insert(att);
    }

    private Long uniqueSourceId() {
        return 800_000_000L + SEQ.incrementAndGet();
    }
}
