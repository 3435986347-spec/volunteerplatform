package com.hengde.activity;

import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dto.ActivityCreateDTO;
import com.hengde.activity.dto.ActivitySlotDTO;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.service.ActivityService;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复制活动（A1）：源活动若已开始/结束/历史/有总结/带审核留痕，副本必须是一条「全新的普通已发布活动」——
 * status=1、runStatus=0、非历史、无实际开始结束、无总结、无发布审核留痕。
 * MySQL + Redis 由 Testcontainers 起。<b>需本机有 Docker。</b>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class ActivityCopyTest {

    private static final long ADMIN = 100L;
    private static final long ADMIN2 = 200L;

    @Autowired
    private ActivityService activityService;
    @Autowired
    private ActivityMapper activityMapper;

    @Test
    void copy_resetsRunLifecycleSummaryHistoricalAndReviewTraces() {
        Long srcId = activityService.publish(base(), ADMIN);

        // 把源活动「弄脏」成一个已结束/历史/带总结/带审核留痕的活动
        Activity src = activityMapper.selectById(srcId);
        src.setStatus(2);                 // 已结束
        src.setRunStatus(2);              // 现场已结束
        src.setActualStartTime(LocalDateTime.of(2026, 6, 1, 9, 0));
        src.setActualEndTime(LocalDateTime.of(2026, 6, 1, 11, 0));
        src.setSummaryText("旧总结");
        src.setSummaryImages("a.jpg,b.jpg");
        src.setSummaryBy(ADMIN);
        src.setSummaryTime(LocalDateTime.of(2026, 6, 1, 12, 0));
        src.setIsHistorical(1);
        src.setPublishRejectReason("旧驳回原因");
        src.setPublishReviewBy(ADMIN);
        src.setPublishReviewTime(LocalDateTime.of(2026, 6, 1, 8, 0));
        activityMapper.updateById(src);

        Long copyId = activityService.copy(srcId, ADMIN2);
        Activity copy = activityMapper.selectById(copyId);

        // 副本 = 全新普通已发布活动
        assertEquals(1, copy.getStatus(), "副本应为已发布");
        assertEquals(0, copy.getRunStatus(), "副本运行态应重置为未开始");
        assertNull(copy.getActualStartTime());
        assertNull(copy.getActualEndTime());
        assertNull(copy.getSummaryText());
        assertNull(copy.getSummaryImages());
        assertNull(copy.getSummaryBy());
        assertNull(copy.getSummaryTime());
        assertEquals(0, copy.getIsHistorical(), "副本不应继承历史标记");
        // 发布审核留痕不得带入副本
        assertNull(copy.getPublishRejectReason());
        assertNull(copy.getPublishReviewBy());
        assertNull(copy.getPublishReviewTime());
        // 其余复制语义
        assertEquals(copy.getId(), copy.getSerialNo(), "副本编号=自增 id");
        assertEquals(ADMIN2, copy.getCreateBy());
        assertTrue(copy.getTitle().endsWith("（复制）"));
    }

    /** 最小可发布活动：6/1 09:00~11:00，一个 09:00~10:00 的时间段。 */
    private ActivityCreateDTO base() {
        ActivitySlotDTO slot = new ActivitySlotDTO();
        slot.setProjectName("项目A");
        slot.setStartTime(LocalDateTime.of(2026, 6, 1, 9, 0));
        slot.setEndTime(LocalDateTime.of(2026, 6, 1, 10, 0));
        slot.setNeedCount(10);

        ActivityCreateDTO t = new ActivityCreateDTO();
        t.setTitle("复制源活动_" + System.nanoTime());
        t.setStartTime(LocalDateTime.of(2026, 6, 1, 9, 0));
        t.setEndTime(LocalDateTime.of(2026, 6, 1, 11, 0));
        t.setSlots(List.of(slot));
        return t;
    }
}
