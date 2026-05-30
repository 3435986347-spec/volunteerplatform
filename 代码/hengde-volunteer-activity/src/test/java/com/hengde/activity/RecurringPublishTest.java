package com.hengde.activity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.dto.ActivityCreateDTO;
import com.hengde.activity.dto.ActivitySlotDTO;
import com.hengde.activity.dto.RecurringActivityDTO;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.service.ActivityService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固定日期周期发布（第 3 批·PR2）验证：显式日期/星期几规则展开/并集去重/空集合与超限拒绝/逐场仍过校验。
 * MySQL + Redis 由 Testcontainers 起（activity 上下文含 Redisson 依赖）。<b>需本机有 Docker。</b>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class RecurringPublishTest {

    private static final long ADMIN = 100L;
    /** 2026-06-01 是周一 */
    private static final LocalDate MON_JUN_1 = LocalDate.of(2026, 6, 1);

    @Autowired
    private ActivityService activityService;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ActivitySlotMapper slotMapper;

    @Test
    void explicitDates_createsActivityPerDate_withShiftedSlot() {
        RecurringActivityDTO dto = base();
        dto.setDates(List.of(MON_JUN_1, MON_JUN_1.plusDays(7), MON_JUN_1.plusDays(14)));

        List<Long> ids = activityService.publishRecurring(dto, ADMIN);
        assertEquals(3, ids.size());

        Set<LocalDate> days = activityMapper.selectBatchIds(ids).stream()
                .map(a -> a.getStartTime().toLocalDate()).collect(Collectors.toSet());
        assertEquals(Set.of(MON_JUN_1, MON_JUN_1.plusDays(7), MON_JUN_1.plusDays(14)), days);

        // 取 6/8 那场，确认时刻保持 09:00、slot 同步平移到 6/8
        Long jun8 = activityMapper.selectBatchIds(ids).stream()
                .filter(a -> a.getStartTime().toLocalDate().equals(MON_JUN_1.plusDays(7)))
                .findFirst().orElseThrow().getId();
        assertEquals(LocalTime.of(9, 0), activityMapper.selectById(jun8).getStartTime().toLocalTime());
        List<ActivitySlot> slots = slotMapper.selectList(Wrappers.<ActivitySlot>lambdaQuery()
                .eq(ActivitySlot::getActivityId, jun8));
        assertEquals(1, slots.size());
        assertEquals(LocalDateTime.of(2026, 6, 8, 9, 0), slots.get(0).getStartTime());
        assertEquals(LocalDateTime.of(2026, 6, 8, 10, 0), slots.get(0).getEndTime());
    }

    @Test
    void weekdayRule_expandsToMatchingDates() {
        RecurringActivityDTO dto = base();
        dto.setRecurStart(MON_JUN_1);          // 周一
        dto.setRecurEnd(MON_JUN_1.plusDays(13)); // 到 6/14（含）
        dto.setWeekdays(List.of(1));            // 仅周一 → 6/1、6/8

        List<Long> ids = activityService.publishRecurring(dto, ADMIN);
        Set<LocalDate> days = activityMapper.selectBatchIds(ids).stream()
                .map(a -> a.getStartTime().toLocalDate()).collect(Collectors.toSet());
        assertEquals(Set.of(MON_JUN_1, MON_JUN_1.plusDays(7)), days);
    }

    @Test
    void unionOfDatesAndRule_dedups() {
        RecurringActivityDTO dto = base();
        dto.setDates(List.of(MON_JUN_1));                 // 6/1
        dto.setRecurStart(MON_JUN_1);
        dto.setRecurEnd(MON_JUN_1.plusDays(7));           // 到 6/8
        dto.setWeekdays(List.of(1));                      // 周一 → 6/1、6/8

        List<Long> ids = activityService.publishRecurring(dto, ADMIN);
        assertEquals(2, ids.size(), "6/1 显式与规则重叠应去重，仅 6/1、6/8");
    }

    @Test
    void noDatesNoRule_rejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> activityService.publishRecurring(base(), ADMIN));
        assertTrue(ex.getMessage().contains("未解析到任何发布日期"));
    }

    @Test
    void slotOutsideRange_rejectedPerInstance() {
        RecurringActivityDTO dto = base();
        // slot 结束超出活动整体结束时间 → 逐场 validateDto 应拒
        dto.getTemplate().getSlots().get(0).setEndTime(LocalDateTime.of(2026, 6, 1, 12, 0));
        dto.setDates(List.of(MON_JUN_1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> activityService.publishRecurring(dto, ADMIN));
        assertTrue(ex.getMessage().contains("活动整体时间范围"));
    }

    @Test
    void exceedCap_rejected() {
        RecurringActivityDTO dto = base();
        dto.setRecurStart(MON_JUN_1);
        dto.setRecurEnd(MON_JUN_1.plusMonths(3));         // ~92 天
        dto.setWeekdays(List.of(1, 2, 3, 4, 5, 6, 7));    // 每天 → >60 场

        BusinessException ex = assertThrows(BusinessException.class,
                () -> activityService.publishRecurring(dto, ADMIN));
        assertTrue(ex.getMessage().contains("场次过多"));
    }

    // ---------- helpers ----------

    /** 模板：6/1 09:00~11:00，一个 09:00~10:00 的时间段。 */
    private RecurringActivityDTO base() {
        ActivitySlotDTO slot = new ActivitySlotDTO();
        slot.setProjectName("项目A");
        slot.setStartTime(LocalDateTime.of(2026, 6, 1, 9, 0));
        slot.setEndTime(LocalDateTime.of(2026, 6, 1, 10, 0));
        slot.setNeedCount(10);

        ActivityCreateDTO t = new ActivityCreateDTO();
        t.setTitle("周期活动模板_" + System.nanoTime());
        t.setStartTime(LocalDateTime.of(2026, 6, 1, 9, 0));
        t.setEndTime(LocalDateTime.of(2026, 6, 1, 11, 0));
        t.setSlots(List.of(slot));

        RecurringActivityDTO dto = new RecurringActivityDTO();
        dto.setTemplate(t);
        return dto;
    }
}
