package com.hengde.user;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.constant.Grade;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.user.dto.MyProfileUpdateDTO;
import com.hengde.user.service.GradeUpgradeService;
import com.hengde.user.service.MyProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 年级每年 9 月升一级（Row 25，V4 个人中心补全批）。
 *
 * <p>升级是<b>全表</b> UPDATE，会动到同一个库里别的用例造的人，所以本类用一组独有的属性强制起<b>自己的</b>上下文与容器。
 * 钉住：普通年级 +1、分界年级挂提示不升、毕业 / 注销 / 游客不动、没记学年的只补基线不升、同一学年重跑什么也不改、
 * 挂着提示没改的人第二年不被重复处理、本人改年级清提示；并发两个任务同时跑，每人只升一次。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = "hengde.user.grade-upgrade.cron=0 0 0 1 1 ?")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class GradeUpgradeServiceTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);

    @Autowired
    private GradeUpgradeService upgradeService;
    @Autowired
    private MyProfileService profileService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void schoolYearRules_idempotency_prompts_andProfileEditClearsThePrompt() {
        jdbc.update("UPDATE volunteer SET grade = NULL, grade_upgrade_year = NULL");
        Long g5 = volunteer(Grade.GRADE_5, 2025, 0, true);
        Long g6 = volunteer(Grade.GRADE_6, 2025, 0, true);
        Long senior3 = volunteer(Grade.SENIOR_3, 2025, 0, true);
        Long college5 = volunteer(Grade.COLLEGE_5, 2025, 0, true);
        Long graduated = volunteer(Grade.GRADUATED, 2025, 0, true);
        Long already = volunteer(Grade.GRADE_3, 2026, 0, true);
        Long noYear = volunteer(Grade.GRADE_4, null, 0, true);
        Long cancelled = volunteer(Grade.GRADE_5, 2025, 2, true);
        Long guest = volunteer(Grade.GRADE_5, 2025, 0, false);

        GradeUpgradeService.Result r = upgradeService.run(LocalDate.of(2026, 9, 2));
        assertEquals(2026, r.schoolYear());
        assertEquals(1, r.upgraded(), "只有五年级那一个人升级：" + r);
        assertEquals(3, r.prompted(), "六年级、高三、大五挂提示：" + r);
        assertEquals(1, r.baselined(), "没记学年的补基线：" + r);
        assertState(g5, 6, 2026, 0);
        assertState(g6, 6, 2026, 1);
        assertState(senior3, 12, 2026, 1);
        assertState(college5, 17, 2026, 1);
        assertState(graduated, 18, 2025, 0);
        assertState(already, 3, 2026, 0);
        assertState(noYear, 4, 2026, 0);
        assertState(cancelled, 5, 2025, 0);
        assertState(guest, 5, 2025, 0);

        GradeUpgradeService.Result again = upgradeService.run(LocalDate.of(2026, 12, 31));
        assertEquals(0, again.upgraded() + again.prompted() + again.baselined(), "同一学年重跑什么也不改：" + again);
        assertEquals(0, upgradeService.run(LocalDate.of(2027, 8, 31)).upgraded(), "8 月 31 日还是 2026 学年");

        // 第二年：刚升到六年级的人挂提示；去年挂着提示没改的人不升、不被重复计数，只是学年前移
        GradeUpgradeService.Result next = upgradeService.run(LocalDate.of(2027, 9, 1));
        assertEquals(2, next.upgraded(), "三年级→四、四年级→五：" + next);
        assertEquals(1, next.prompted(), "只有今年新到分界的那一个：" + next);
        assertState(g5, 6, 2027, 1);
        assertState(g6, 6, 2027, 1);

        // 本人改年级：提示清除，学年置为当前，下一个 9 月照常升
        MyProfileUpdateDTO dto = new MyProfileUpdateDTO();
        dto.setGrade(Grade.GRADE_7.getCode());
        profileService.updateMyProfile(g6, dto);
        Map<String, Object> row = row(g6);
        assertEquals(7, ((Number) row.get("grade")).intValue());
        assertEquals(0, ((Number) row.get("grade_prompt_pending")).intValue());
        assertEquals(true, profileService.getMyProfile(g5).getGradePromptPending());
    }

    @Test
    void twoRunsAtOnce_upgradeEachPersonOnlyOnce() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add(volunteer(Grade.GRADE_1, 2030, 0, true));
        }
        int n = 4;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<GradeUpgradeService.Result>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return upgradeService.run(LocalDate.of(2031, 9, 10));
            }));
        }
        for (Future<GradeUpgradeService.Result> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        for (Long id : ids) {
            assertState(id, 2, 2031, 0);
        }
    }

    private Long volunteer(Grade grade, Integer year, int status, boolean registered) {
        Volunteer v = new Volunteer();
        v.setOpenid("test:grade:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setRealName("年级" + SEQ.get());
        v.setStatus(status);
        v.setManagerFlag(0);
        v.setGrade(grade);
        v.setGradeUpgradeYear(year);
        v.setGradePromptPending(0);
        if (registered) {
            v.setRegisterTime(LocalDateTime.now());
        }
        volunteerMapper.insert(v);
        return v.getId();
    }

    private Map<String, Object> row(Long id) {
        return jdbc.queryForMap("SELECT grade, grade_upgrade_year, grade_prompt_pending FROM volunteer WHERE id = ?", id);
    }

    private void assertState(Long id, int grade, int year, int pending) {
        Map<String, Object> r = row(id);
        String who = "志愿者 " + id + " " + r;
        assertEquals(grade, ((Number) r.get("grade")).intValue(), who);
        assertEquals(year, ((Number) r.get("grade_upgrade_year")).intValue(), who);
        assertEquals(pending, ((Number) r.get("grade_prompt_pending")).intValue(), who);
    }
}
