package com.hengde.social.service;

import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「最热」小时桶（V4规划 D6）：本小时 + 上一小时按剩余比例折算；同分新帖在前。
 *
 * <p>用一个<b>很久以前的固定时刻</b>做「现在」，不和别的用例（按真实时间记热度）抢同一个桶。<b>需本机 Docker</b>。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class HotRankServiceTest {

    @Autowired
    private HotRankService hotRankService;

    @Test
    void currentHourPlusDecayedPreviousHour() {
        LocalDateTime now = LocalDateTime.of(1999, 3, 3, 10, 30);   // 本小时已过去一半 → 上一小时的分打五折
        long a = 910_001L;
        long b = 910_002L;
        long c = 910_003L;
        hotRankService.bump(a, 5, now.minusHours(1));   // 上一小时 5 分 → 2.5
        hotRankService.bump(b, 3, now);                 // 本小时 3 分
        hotRankService.bump(c, 1, now.minusHours(1));   // 上一小时 1 分 → 0.5
        hotRankService.bump(c, 2, now);                 // + 本小时 2 分 → 2.5（与 a 同分，id 大的在前）
        assertEquals(List.of(b, c, a), hotRankService.topIds(now, 10));

        LocalDateTime twoHoursLater = now.plusHours(2);
        assertEquals(List.of(), hotRankService.topIds(twoHoursLater, 10), "两个小时前的热度不再算");

        LocalDateTime nextHourStart = LocalDateTime.of(1999, 3, 3, 11, 0);   // 整点刚过：上一小时（10 点）满额带过来，9 点的不算
        assertEquals(List.of(b, c), hotRankService.topIds(nextHourStart, 10));
        assertTrue(hotRankService.topIds(now, 1).size() == 1, "limit 生效");
    }
}
