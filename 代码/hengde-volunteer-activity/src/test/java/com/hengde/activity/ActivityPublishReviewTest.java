package com.hengde.activity;

import com.hengde.activity.dao.ActivityLeaderMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dto.ActivityCreateDTO;
import com.hengde.activity.dto.ActivitySlotDTO;
import com.hengde.activity.dto.ActivityUpdateDTO;
import com.hengde.activity.dto.BackfillRequestDTO;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityLeader;
import com.hengde.activity.service.ActivityBackfillService;
import com.hengde.activity.service.ActivityLeaderService;
import com.hengde.activity.service.ActivityReviewService;
import com.hengde.activity.service.ActivityService;
import com.hengde.activity.service.AttendanceService;
import com.hengde.activity.vo.ActivityAdminDetailVO;
import com.hengde.activity.vo.ActivityListVO;
import com.hengde.activity.vo.ActivityReviewVO;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动发布审核（V19）验证：小程序提交落「待审核」、不直接上线；部长审核通过上线 / 驳回记原因 / CAS 防重复。
 * MySQL + Redis 由 Testcontainers 起（activity 上下文含 Redisson）。<b>需本机有 Docker。</b>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class ActivityPublishReviewTest {

    private static final int STATUS_PUBLISHED = 1;
    private static final int STATUS_PENDING_REVIEW = 4;
    private static final int STATUS_REJECTED = 5;
    private static final long ADMIN = 100L;
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private ActivityService activityService;
    @Autowired
    private ActivityReviewService activityReviewService;
    @Autowired
    private ActivityLeaderService activityLeaderService;
    @Autowired
    private ActivityBackfillService activityBackfillService;
    @Autowired
    private AttendanceService attendanceService;
    @Autowired
    private ActivityLeaderMapper leaderMapper;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;

    @Test
    void submitForReview_landsPendingNotPublished() {
        Long vid = insertVolunteer("提交人甲");
        Long id = activityService.submitForReview(base(), vid);

        Activity a = activityMapper.selectById(id);
        assertEquals(STATUS_PENDING_REVIEW, a.getStatus(), "小程序提交应落待审核，不直接上线");
        assertEquals(vid, a.getCreateBy(), "提交人记当前志愿者");
    }

    @Test
    void pendingReviews_listsWithSubmitterName() {
        Long vid = insertVolunteer("提交人乙");
        Long id = activityService.submitForReview(base(), vid);

        PageResult<ActivityReviewVO> page = activityReviewService.reviews(new PageQuery(), null);
        ActivityReviewVO row = page.getRecords().stream().filter(v -> v.getId().equals(id)).findFirst().orElseThrow();
        assertEquals("提交人乙", row.getSubmitterName(), "待审列表应带提交人姓名");
        assertEquals(vid, row.getSubmitterId());
    }

    @Test
    void approve_putsActivityLive() {
        Long vid = insertVolunteer("提交人丙");
        Long id = activityService.submitForReview(base(), vid);

        activityReviewService.approve(id, ADMIN);

        Activity a = activityMapper.selectById(id);
        assertEquals(STATUS_PUBLISHED, a.getStatus(), "审核通过后活动上线");
        assertEquals(ADMIN, a.getPublishReviewBy());
        assertNull(a.getPublishRejectReason());
    }

    @Test
    void reject_marksRejectedWithReason() {
        Long vid = insertVolunteer("提交人丁");
        Long id = activityService.submitForReview(base(), vid);

        activityReviewService.reject(id, "信息不全", ADMIN);

        Activity a = activityMapper.selectById(id);
        assertEquals(STATUS_REJECTED, a.getStatus());
        assertEquals("信息不全", a.getPublishRejectReason());
        assertEquals(ADMIN, a.getPublishReviewBy());
    }

    @Test
    void approve_nonPending_rejectedByCas() {
        Long vid = insertVolunteer("提交人戊");
        Long id = activityService.submitForReview(base(), vid);
        activityReviewService.approve(id, ADMIN); // 已上线

        BusinessException ex = assertThrows(BusinessException.class,
                () -> activityReviewService.approve(id, ADMIN));
        assertTrue(ex.getMessage().contains("不在待审核状态"), "对非待审活动再审核应被 CAS 拦下");
    }

    @Test
    void directPublish_notInPendingQueue() {
        // 后台 /a 直接发布的活动不进审核队列
        Long beforeId = activityService.publish(base(), ADMIN);
        Activity a = activityMapper.selectById(beforeId);
        assertEquals(STATUS_PUBLISHED, a.getStatus(), "后台直接发布仍立即上线、不待审");
    }

    @Test
    void reviewDomain_excludedFromAdminMenuList_visibleOnlyViaReviewSide() {
        Long vid = insertVolunteer("提交人己");
        Long pendingId = activityService.submitForReview(base(), vid);
        Long publishedId = activityService.publish(base(), ADMIN);

        // 常规活动管理列表（activity:menu）排除待审核/驳回，已发布的在
        List<ActivityListVO> list = activityService.listForAdmin(new PageQuery(), null, null).getRecords();
        assertTrue(list.stream().anyMatch(a -> a.getId().equals(publishedId)), "已发布活动在常规列表");
        assertTrue(list.stream().noneMatch(a -> a.getId().equals(pendingId)), "待审核活动不应出现在常规列表");

        // 常规详情对待审核活动报「不存在」；审核详情（publish-audit）可见全字段
        assertThrows(BusinessException.class, () -> activityService.detailForAdmin(pendingId));
        ActivityAdminDetailVO rd = activityService.reviewDetail(pendingId);
        assertEquals(pendingId, rd.getId());
    }

    @Test
    void copy_clearsReviewTraces() {
        // 审核通过的活动 status=1 但带 publish_review_by 留痕；复制它不应继承审核留痕
        Long vid = insertVolunteer("提交人庚");
        Long id = activityService.submitForReview(base(), vid);
        activityReviewService.approve(id, ADMIN); // status=1 + publishReviewBy 留痕

        Long copyId = activityService.copy(id, ADMIN);
        Activity c = activityMapper.selectById(copyId);
        assertEquals(STATUS_PUBLISHED, c.getStatus(), "复制出的活动直接已发布");
        assertNull(c.getPublishRejectReason(), "复制不继承驳回原因");
        assertNull(c.getPublishReviewBy(), "复制不继承审核人");
        assertNull(c.getPublishReviewTime(), "复制不继承审核时间");
    }

    @Test
    void writeOps_onUnderReviewActivity_allRejected() {
        // 审核域（待审核）活动不可经常规写接口绕开审核：update/delete/copy 一律按「不存在」拒绝
        Long vid = insertVolunteer("提交人壬");
        Long pendingId = activityService.submitForReview(base(), vid);

        assertThrows(BusinessException.class, () -> activityService.update(pendingId, new ActivityUpdateDTO()),
                "待审活动不可常规修改");
        assertThrows(BusinessException.class, () -> activityService.delete(pendingId),
                "待审活动不可常规删除");
        assertThrows(BusinessException.class, () -> activityService.copy(pendingId, ADMIN),
                "待审活动不可复制（否则绕开审核直接发布同内容）");
    }

    @Test
    void leaderOps_onUnderReviewActivity_rejected() {
        // 审核域活动不可经负责人指派/查看绕开审核（leaderType=2 管理团队不受报名校验限制，是主要风险点）
        Long vid = insertVolunteer("提交人甲乙");
        Long pendingId = activityService.submitForReview(base(), vid);

        assertThrows(BusinessException.class,
                () -> activityLeaderService.assign(pendingId, 2, 999L, ADMIN), "待审活动不可指派负责人");
        assertThrows(BusinessException.class,
                () -> activityLeaderService.list(pendingId), "待审活动不可查负责人列表");
    }

    @Test
    void staleLeaderRow_onUnderReviewActivity_hiddenFromVolunteerLeaderViews() {
        // 模拟历史/脏数据：直接插一条指向待审活动的志愿者负责人行（绕过已加守卫的 assign），
        // /v 负责人路径仍不得泄露未上线活动
        Long vid = insertVolunteer("负责人甲");
        Long pendingId = activityService.submitForReview(base(), vid);
        ActivityLeader stale = new ActivityLeader();
        stale.setActivityId(pendingId);
        stale.setLeaderType(1);
        stale.setVolunteerId(vid);
        stale.setAssignedBy(ADMIN);
        stale.setAssignedTime(LocalDateTime.now());
        leaderMapper.insert(stale);

        assertTrue(attendanceService.myLedActivities(vid).stream()
                        .noneMatch(m -> m.getActivityId().equals(pendingId)),
                "负责人「我负责的活动」列表应排除待审活动");
        assertThrows(BusinessException.class, () -> attendanceService.leaderDetail(pendingId),
                "负责人详情对待审活动报不存在");
        assertThrows(BusinessException.class, () -> activityLeaderService.requireVolunteerLeader(pendingId, vid),
                "负责人动作入口对待审活动报不存在（即便有 leader 行）");
    }

    @Test
    void backfill_onUnderReviewActivity_rejected() {
        // 审核域活动不可补录——否则知道 id 的组织部能给未上线/已驳回活动落服务记录与积分
        Long vid = insertVolunteer("提交人丙丁");
        Long pendingId = activityService.submitForReview(base(), vid);

        // 守卫在志愿者匹配之前即触发，故用空 DTO 也会被拦
        assertThrows(BusinessException.class,
                () -> activityBackfillService.requestBackfill(pendingId, new BackfillRequestDTO(), ADMIN),
                "待审活动不可补录");
    }

    @Test
    void reviews_statusRejected_listsRejectedRecord() {
        Long vid = insertVolunteer("提交人癸");
        Long id = activityService.submitForReview(base(), vid);
        activityReviewService.reject(id, "材料不符", ADMIN); // status=5

        // 默认（待审）列表不含已驳回；显式 status=5 才查得到——驳回记录有列表入口
        assertTrue(activityReviewService.reviews(new PageQuery(), null).getRecords()
                .stream().noneMatch(v -> v.getId().equals(id)), "默认待审列表不含已驳回");
        assertTrue(activityReviewService.reviews(new PageQuery(), STATUS_REJECTED).getRecords()
                .stream().anyMatch(v -> v.getId().equals(id)), "status=5 可查到已驳回");
    }

    @Test
    void reviewDetail_exposesRejectReasonAndTrail() {
        Long vid = insertVolunteer("提交人子");
        Long id = activityService.submitForReview(base(), vid);
        activityReviewService.reject(id, "时间冲突", ADMIN);

        ActivityAdminDetailVO d = activityService.reviewDetail(id);
        assertEquals("时间冲突", d.getPublishRejectReason(), "审核详情应回显驳回原因");
        assertEquals(ADMIN, d.getPublishReviewBy(), "审核详情应回显审核人");
        assertNotNull(d.getPublishReviewTime(), "审核详情应回显审核时间");
    }

    @Test
    void reject_overlongReason_rejectedByService() {
        Long vid = insertVolunteer("提交人辛");
        Long id = activityService.submitForReview(base(), vid);
        String tooLong = "x".repeat(513);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> activityReviewService.reject(id, tooLong, ADMIN));
        assertTrue(ex.getMessage().contains("不超过"), "service 层兜底拦超长驳回原因");
    }

    @Test
    void submitForReview_missingRequiredFields_rejected() {
        // 接口层兜底：志愿者提交流对封面/地点/要求/积分/需求人数缺失或非法一律拒绝（防绕过前端 * 直发空值）
        Long vid = insertVolunteer("缺必填");

        ActivityCreateDTO noCover = base();
        noCover.setCoverImageUrl("  ");
        assertTrue(assertThrows(BusinessException.class,
                () -> activityService.submitForReview(noCover, vid)).getMessage().contains("封面"));

        ActivityCreateDTO noLoc = base();
        noLoc.setLocation(null);
        assertThrows(BusinessException.class, () -> activityService.submitForReview(noLoc, vid));

        ActivityCreateDTO noReq = base();
        noReq.setRequirement("");
        assertThrows(BusinessException.class, () -> activityService.submitForReview(noReq, vid));

        ActivityCreateDTO noPoints = base();
        noPoints.setPointsBase(null);
        assertThrows(BusinessException.class, () -> activityService.submitForReview(noPoints, vid));

        ActivityCreateDTO badNeed = base();
        badNeed.getSlots().get(0).setNeedCount(0);
        assertTrue(assertThrows(BusinessException.class,
                () -> activityService.submitForReview(badNeed, vid)).getMessage().contains("需求人数"));

        // service 边界兜底：slots=null/[]/[null] 走友好业务异常，不落到下游 NPE（防内部直调绕过 @NotEmpty/@Valid）
        ActivityCreateDTO nullSlots = base();
        nullSlots.setSlots(null);
        assertTrue(assertThrows(BusinessException.class,
                () -> activityService.submitForReview(nullSlots, vid)).getMessage().contains("时间段"));

        ActivityCreateDTO emptySlots = base();
        emptySlots.setSlots(List.of());
        assertThrows(BusinessException.class, () -> activityService.submitForReview(emptySlots, vid));

        ActivityCreateDTO nullElem = base();
        nullElem.setSlots(java.util.Collections.singletonList((ActivitySlotDTO) null));
        assertThrows(BusinessException.class, () -> activityService.submitForReview(nullElem, vid));
    }

    @Test
    void directPublish_lenientOnVolunteerRequiredFields() {
        // 后台 /a 直发保留 override：缺封面/地点/要求/积分（志愿者端必填）仍可发布、走默认值
        LocalDateTime start = LocalDateTime.now().plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0);
        ActivitySlotDTO slot = new ActivitySlotDTO();
        slot.setProjectName("项目A");
        slot.setStartTime(start);
        slot.setEndTime(start.plusHours(1));
        slot.setNeedCount(5);
        ActivityCreateDTO minimal = new ActivityCreateDTO();
        minimal.setTitle("最小直发_" + System.nanoTime());
        minimal.setStartTime(start);
        minimal.setEndTime(start.plusHours(2));
        minimal.setSlots(List.of(slot));

        Long id = activityService.publish(minimal, ADMIN);
        Activity a = activityMapper.selectById(id);
        assertEquals(STATUS_PUBLISHED, a.getStatus(), "后台直发不受志愿者端必填约束");
        assertEquals(0, a.getPointsBase(), "缺积分走默认 0");
    }

    // ---------- helpers ----------

    private Long insertVolunteer(String name) {
        Volunteer v = new Volunteer();
        v.setOpenid("test:review:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setRealName(name);
        v.setStatus(0);
        volunteerMapper.insert(v);
        return v.getId();
    }

    /** 模板：明天 09:00~11:00，一个 09:00~10:00 的时间段。含志愿者端必填项（封面/地点/要求/积分/需求人数）。 */
    private ActivityCreateDTO base() {
        LocalDateTime start = LocalDateTime.now().plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0);
        ActivitySlotDTO slot = new ActivitySlotDTO();
        slot.setProjectName("项目A");
        slot.setStartTime(start);
        slot.setEndTime(start.plusHours(1));
        slot.setNeedCount(10);

        ActivityCreateDTO dto = new ActivityCreateDTO();
        dto.setTitle("待审活动_" + System.nanoTime());
        dto.setStartTime(start);
        dto.setEndTime(start.plusHours(2));
        dto.setCoverImageUrl("https://example.com/cover.jpg");
        dto.setLocation("御景雅苑");
        dto.setRequirement("完成实名注册即可报名");
        dto.setPointsBase(10);
        dto.setSlots(List.of(slot));
        return dto;
    }
}
