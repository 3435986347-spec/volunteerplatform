package com.hengde.activity;

import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityEnrollment;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.service.ActivityService;
import com.hengde.activity.vo.ActivityRegistrantVO;
import com.hengde.activity.vo.ActivityVolunteerDetailVO;
import com.hengde.activity.vo.RecommendActivityVO;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 志愿者端活动查询验证：列表仅露已发布、详情按 id 直达非已发布一律「不存在」。
 *
 * <p>schema 由 common 的 Flyway 迁移在 Testcontainers MySQL 建库时执行。<b>需本机有 Docker。</b>
 * 数据在同一容器内跨测试方法累积，故各用例用唯一 title 关键词隔离自己的数据。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class VolunteerActivityQueryTest {

    private static final int STATUS_PUBLISHED = 1;
    private static final int STATUS_FINISHED = 2;
    private static final int STATUS_CANCELLED = 3;

    @Autowired
    private ActivityService activityService;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ActivitySlotMapper activitySlotMapper;
    @Autowired
    private ActivityEnrollmentMapper enrollmentMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;

    @Test
    void listForVolunteer_onlyReturnsPublished() {
        String kw = "LIST_" + System.nanoTime();
        insertActivity(kw + "_published", STATUS_PUBLISHED);
        insertActivity(kw + "_cancelled", STATUS_CANCELLED);
        insertActivity(kw + "_finished", STATUS_FINISHED);

        PageQuery query = new PageQuery();
        query.setPage(1);
        query.setSize(10);
        // 领域模块测试上下文无分页拦截器（它在 api 的 MybatisPlusConfig），故断言 records 内容而非 total：
        // 无拦截器时 selectPage 不加 LIMIT，records 即为全部匹配行，正好验证 status=1 过滤。
        List<RecommendActivityVO> records = activityService.listForVolunteer(query, kw).getRecords();

        assertEquals(1, records.size(), "仅 1 个已发布活动应被列出");
        assertTrue(records.get(0).getTitle().endsWith("_published"));
        assertEquals(STATUS_PUBLISHED, records.get(0).getStatus());
    }

    @Test
    void detailForVolunteer_publishedReturnsVoWithSlots() {
        Long id = insertActivity("DETAIL_" + System.nanoTime(), STATUS_PUBLISHED);
        insertSlot(id, "上午场");
        insertSlot(id, "下午场");

        ActivityVolunteerDetailVO vo = activityService.detailForVolunteer(id);

        assertNotNull(vo);
        assertEquals(id, vo.getId());
        assertEquals(2, vo.getSlots().size(), "详情应带出 2 个时间段");
    }

    @Test
    void detailForVolunteer_exposesNeedCountAndEnrolledCount() {
        // 详情页「已报名 X/Y」：Y=各时间段 need_count 之和，X=活跃报名去重人数（之前 VO 未暴露致前端显示 X/0）
        Long id = insertActivity("DETAILCOUNT_" + System.nanoTime(), STATUS_PUBLISHED);
        Long slot1 = insertSlotReturning(id, 20);
        Long slot2 = insertSlotReturning(id, 5);
        Long zhou = insertVolunteer("周怡汐");
        Long li = insertVolunteer("李明");
        insertActiveEnrollment(id, slot1, zhou);
        insertActiveEnrollment(id, slot2, li);

        ActivityVolunteerDetailVO vo = activityService.detailForVolunteer(id);

        assertEquals(25, vo.getNeedCount().intValue(), "招募名额=各时间段 need_count 之和");
        assertEquals(2L, vo.getEnrolledCount().longValue(), "已报名=活跃报名去重志愿者数");
        // 报名详情：去掉预置假人后回真实报名者，显示完整姓名（2026-06-27 用户改：原仅姓氏）
        assertEquals(2, vo.getRegistrants().size(), "报名详情应含两名报名人");
        List<String> names = vo.getRegistrants().stream().map(ActivityRegistrantVO::getName).toList();
        assertTrue(names.contains("周怡汐") && names.contains("李明"), "报名详情显示完整姓名");
    }

    @Test
    void detailForVolunteer_parsesServiceGuarantees() {
        // 服务保障：DB 存逗号分隔 key → 详情回 List<key>，过滤未知 key、按规范顺序输出
        Long id = insertActivity("GUARANTEE_" + System.nanoTime(), STATUS_PUBLISHED);
        Activity a = activityMapper.selectById(id);
        a.setServiceGuarantees("other,water,zzz,insurance"); // 乱序 + 未知 key
        activityMapper.updateById(a);

        ActivityVolunteerDetailVO vo = activityService.detailForVolunteer(id);
        assertEquals(List.of("water", "insurance", "other"), vo.getServiceGuarantees(),
                "服务保障按规范顺序返回、未知 key(zzz) 被过滤");
    }

    @Test
    void detailForVolunteer_needCountUnlimitedWhenAnySlotZero() {
        // 任一时间段 need_count=0（不限）→ 详情总名额返回 0（前端显示「不限」），不被 sum 吞成 5
        Long id = insertActivity("UNLIMITED_" + System.nanoTime(), STATUS_PUBLISHED);
        insertSlotReturning(id, 0);
        insertSlotReturning(id, 5);

        ActivityVolunteerDetailVO vo = activityService.detailForVolunteer(id);
        assertEquals(0, vo.getNeedCount().intValue(), "任一段不限则整场不限(0)");
    }

    @Test
    void detailForVolunteer_cancelledThrows() {
        Long id = insertActivity("CANCELLED_" + System.nanoTime(), STATUS_CANCELLED);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> activityService.detailForVolunteer(id), "已取消活动对志愿者端应视为不存在");
        assertEquals("活动不存在", ex.getMessage());
    }

    @Test
    void detailForVolunteer_finishedThrows() {
        Long id = insertActivity("FINISHED_" + System.nanoTime(), STATUS_FINISHED);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> activityService.detailForVolunteer(id), "已结束活动对志愿者端应视为不存在");
        assertEquals("活动不存在", ex.getMessage());
    }

    @Test
    void detailForVolunteer_notExistThrows() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> activityService.detailForVolunteer(99999999L), "不存在的活动应抛业务异常");
        assertEquals("活动不存在", ex.getMessage());
    }

    @Test
    void listForVolunteer_recommendSort_quotaFirst_andEnrolledCount() {
        String kw = "RECO_" + System.nanoTime();
        // A_full：开始时间更晚，但名额已满（1 需求 / 1 报名）
        Long full = insertActivityAt(kw + "_full", STATUS_PUBLISHED, LocalDateTime.now().plusDays(10));
        Long fullSlot = insertSlotReturning(full, 1);
        insertActiveEnrollment(full, fullSlot, 9001L);
        // A_quota：开始时间更早，但仍有名额（5 需求 / 1 报名）
        Long quota = insertActivityAt(kw + "_quota", STATUS_PUBLISHED, LocalDateTime.now().plusDays(1));
        Long quotaSlot = insertSlotReturning(quota, 5);
        insertActiveEnrollment(quota, quotaSlot, 9002L);

        PageQuery query = new PageQuery();
        query.setPage(1);
        query.setSize(10);
        List<RecommendActivityVO> records = activityService.listForVolunteer(query, kw).getRecords();

        assertEquals(2, records.size());
        // 有名额优先：A_quota 排在 A_full 之前，尽管它开始时间更早
        assertEquals(quota, records.get(0).getId(), "有名额的活动应排在满员活动之前");
        assertEquals(1, records.get(0).getHasQuota().intValue());
        assertEquals(0, records.get(1).getHasQuota().intValue());
        // 报名人数带出
        assertEquals(1L, records.get(0).getEnrolledCount().longValue());
        assertEquals(1L, records.get(1).getEnrolledCount().longValue());
        // 招募名额总数带出（home 卡片「X/Y」的 Y，修复此前恒显示 X/0）
        assertEquals(5, records.get(0).getNeedCount().intValue(), "有名额活动名额=各段 need_count 之和");
        assertEquals(1, records.get(1).getNeedCount().intValue());
    }

    private Long insertActivity(String title, int status) {
        return insertActivityAt(title, status, LocalDateTime.now().plusDays(7));
    }

    private Long insertActivityAt(String title, int status, LocalDateTime start) {
        Activity a = new Activity();
        a.setTitle(title);
        a.setStartTime(start);
        a.setEndTime(start.plusHours(8));
        a.setStatus(status);
        activityMapper.insert(a);
        return a.getId();
    }

    private Long insertSlotReturning(Long activityId, int needCount) {
        ActivitySlot slot = new ActivitySlot();
        slot.setActivityId(activityId);
        slot.setProjectName("项目_" + System.nanoTime());
        slot.setStartTime(LocalDateTime.now().plusDays(1));
        slot.setEndTime(LocalDateTime.now().plusDays(1).plusHours(2));
        slot.setNeedCount(needCount);
        activitySlotMapper.insert(slot);
        return slot.getId();
    }

    private Long insertVolunteer(String realName) {
        Volunteer v = new Volunteer();
        v.setOpenid("test:reg:" + System.nanoTime());
        v.setRealName(realName);
        v.setStatus(0);
        volunteerMapper.insert(v);
        return v.getId();
    }

    private void insertActiveEnrollment(Long activityId, Long slotId, Long volunteerId) {
        ActivityEnrollment e = new ActivityEnrollment();
        e.setActivityId(activityId);
        e.setSlotId(slotId);
        e.setVolunteerId(volunteerId);
        e.setStatus(1);
        e.setEnrollTime(LocalDateTime.now());
        enrollmentMapper.insert(e);
    }

    private void insertSlot(Long activityId, String projectName) {
        ActivitySlot slot = new ActivitySlot();
        slot.setActivityId(activityId);
        slot.setProjectName(projectName);
        slot.setStartTime(LocalDateTime.now().plusDays(7));
        slot.setEndTime(LocalDateTime.now().plusDays(7).plusHours(3));
        slot.setNeedCount(5);
        activitySlotMapper.insert(slot);
    }
}
