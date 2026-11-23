package com.hengde.user;

import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.user.service.VolunteerCardService;
import com.hengde.user.vo.VolunteerCardVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 志愿者证（V4 志愿者证批，Row 26，D11 / Q5）：发证 / 令牌形状与稳定 / 公开核验只给那几项且姓名只留姓 / 重置即废旧码 /
 * 各种无效一律同一句话 / 并发第一次打开只发一张。没配小程序 AppID，码图走普通二维码回退（小程序码那一支见 {@code VolunteerCardMiniappCodeTest}）。
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class VolunteerCardServiceTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);

    @Autowired
    private VolunteerCardService cardService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ActivitySlotMapper slotMapper;
    @Autowired
    private ActivityAttendanceMapper attendanceMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void issue_onlyForActiveRegistered_tokenShapeStable() {
        Long tourist = volunteer(false, 0, "游客");
        assertMessage("请先完成实名注册", () -> cardService.myCard(tourist));
        Long disabled = volunteer(true, 1, "停用者");
        assertMessage("账号状态异常", () -> cardService.myCard(disabled));

        Long me = volunteer(true, 0, "张三丰");
        VolunteerCardVO.Mine first = cardService.myCard(me);
        assertEquals(String.valueOf(me), first.getNo());
        assertEquals("张三丰", first.getRealName(), "本人看完整姓名");
        assertEquals("志愿者", first.getDuty());
        assertEquals("QR_CODE", first.getQrType(), "没配小程序 AppID：回退普通二维码");
        assertTrue(first.getQrImage().startsWith("data:image/png;base64,"));
        String token = tokenOf(first);
        assertTrue(token.matches("[A-Za-z0-9_-]{32}"), "32 个字符（小程序码 scene 上限）：" + token);
        // 不能写成「不包含 id 这串数字」：id 只有一两位时随机令牌里碰巧出现的概率很高（基线就这么红过一次）
        assertFalse(token.matches("\\d+"), "令牌不是（补零的）志愿者 id：" + token);

        VolunteerCardVO.Mine again = cardService.myCard(me);
        assertEquals(token, tokenOf(again), "再打开还是同一张证");
        assertEquals(first.getIssueTime(), again.getIssueTime());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_volunteer_card WHERE volunteer_id = ?", Integer.class, me));
    }

    @Test
    void verify_publicFieldsOnly_maskedName_statsSameAsRanking() {
        Long me = volunteer(true, 0, "李小龙");
        Long activityId = activity(1);
        attendance(activityId, me, 90, 1);
        attendance(activity(1), me, 60, 1);
        attendance(activity(1), me, 45, 0);     // 秘书部没确认：算次数、不算时长
        attendance(activity(0), me, 300, 1);    // 草稿活动上的脏考勤：都不算
        String token = tokenOf(cardService.myCard(me));

        VolunteerCardVO.Public pub = cardService.verify(token);
        assertEquals("李**", pub.getMaskedName());
        assertEquals(String.valueOf(me), pub.getNo());
        assertEquals(new BigDecimal("2.5"), pub.getServiceHours(), "90 + 60 分钟，已确认、真实活动上的");
        assertEquals(3L, pub.getActivityCount());
        assertEquals(new BigDecimal("2.5"), cardService.myCard(me).getServiceHours(), "本人证件与核验同一口径");

        Set<String> fields = new HashSet<>();
        for (Field f : VolunteerCardVO.Public.class.getDeclaredFields()) {
            fields.add(f.getName());
        }
        assertEquals(Set.of("no", "maskedName", "avatarUrl", "registerTime", "serviceHours", "activityCount", "verifyTime"), fields,
                "公开核验只给 Q5 那几项：不许长出手机号、学校、完整姓名");
    }

    @Test
    void reset_killsTheOldCode_andInvalidCasesShareOneMessage() {
        Long me = volunteer(true, 0, "王五");
        String old = tokenOf(cardService.myCard(me));
        cardService.verify(old);

        VolunteerCardVO.Mine renewed = cardService.reset(me);
        String fresh = tokenOf(renewed);
        assertNotEquals(old, fresh);
        assertMessage("志愿者证无效或已失效", () -> cardService.verify(old));
        assertEquals(String.valueOf(me), cardService.verify(fresh).getNo());
        assertEquals(1, jdbc.queryForObject("SELECT reset_count FROM user_volunteer_card WHERE volunteer_id = ?", Integer.class, me));

        // 令牌区分大小写（token 列 ascii_bin）：翻转其中一个字母就是另一个令牌
        char[] flipped = fresh.toCharArray();
        int at = 0;
        while (!Character.isLetter(flipped[at])) {
            at++;
        }
        flipped[at] = Character.isUpperCase(flipped[at]) ? Character.toLowerCase(flipped[at]) : Character.toUpperCase(flipped[at]);
        assertMessage("志愿者证无效或已失效", () -> cardService.verify(new String(flipped)));
        assertMessage("志愿者证无效或已失效", () -> cardService.verify(String.valueOf(me)));
        assertMessage("志愿者证无效或已失效", () -> cardService.verify("A".repeat(32)));
        assertMessage("志愿者证无效或已失效", () -> cardService.verify(null));
        assertMessage("志愿者证无效或已失效", () -> cardService.verify(fresh + "' OR '1'='1"));

        jdbc.update("UPDATE volunteer SET status = 2 WHERE id = ?", me);
        assertMessage("志愿者证无效或已失效", () -> cardService.verify(fresh));
    }

    @Test
    void eightThreadsOpenTheCardAtOnce_oneCard() throws Exception {
        Long me = volunteer(true, 0, "赵六");
        int n = 8;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<VolunteerCardVO.Mine>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                barrier.await();
                return cardService.myCard(me);
            }));
        }
        Set<String> tokens = new HashSet<>();
        for (Future<VolunteerCardVO.Mine> f : fs) {
            tokens.add(tokenOf(f.get(30, TimeUnit.SECONDS)));
        }
        pool.shutdownNow();
        assertEquals(1, tokens.size(), "都拿到同一张证：" + tokens);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_volunteer_card WHERE volunteer_id = ?", Integer.class, me));
    }

    /**
     * 压测：40 人 × 16 线程 800 次乱序打开证件 / 重置 / 拿（可能已过期的）旧令牌核验 / 拿垃圾令牌核验。
     * 断言：每人一张证、全部令牌互不相同、每人库里的当前令牌核验得通、重置次数＝成功重置的次数、零非业务异常。
     */
    @org.junit.jupiter.api.Tag("stress")
    @Test
    void cardChurn_invariantsHold() throws Exception {
        List<Long> people = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            people.add(volunteer(true, 0, "压测" + i));
        }
        java.util.Map<Long, java.util.concurrent.atomic.AtomicInteger> resets = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Map<Long, String> lastSeen = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Map<String, java.util.concurrent.atomic.AtomicInteger> tally = new java.util.concurrent.ConcurrentHashMap<>();
        List<Throwable> unexpected = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        int ops = 800;
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long start = System.currentTimeMillis();
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(pool.submit(() -> {
                java.util.Random rnd = new java.util.Random();
                while (seq.getAndIncrement() < ops) {
                    Long who = people.get(rnd.nextInt(people.size()));
                    int kind = rnd.nextInt(100);
                    String label;
                    try {
                        if (kind < 35) {
                            label = "打开证件";
                            lastSeen.put(who, tokenOf(cardService.myCard(who)));
                        } else if (kind < 50) {
                            label = "重置";
                            lastSeen.put(who, tokenOf(cardService.reset(who)));
                            resets.computeIfAbsent(who, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                        } else if (kind < 90) {
                            label = "核验";
                            String token = lastSeen.get(who);
                            if (token == null) {
                                throw new BusinessException("还没打开过");
                            }
                            cardService.verify(token);
                        } else {
                            label = "垃圾令牌";
                            cardService.verify(VolunteerCardServiceTestSupport.randomToken(rnd));
                        }
                        tally.computeIfAbsent(label + "·成功", k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                    } catch (BusinessException e) {
                        tally.computeIfAbsent("拒绝·" + e.getMessage(), k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                    } catch (Throwable e) {
                        unexpected.add(e);
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : fs) {
            f.get(3, TimeUnit.MINUTES);
        }
        pool.shutdownNow();
        System.out.println("==== 志愿者证压测：" + ops + " 次 / " + threads + " 线程 / " + (System.currentTimeMillis() - start)
                + " ms ====\n  " + new java.util.TreeMap<>(tally));
        assertTrue(unexpected.isEmpty(), "不应有非业务异常：" + unexpected.stream().limit(3).map(String::valueOf).toList());
        String in = String.join(",", people.stream().map(String::valueOf).toList());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT volunteer_id FROM user_volunteer_card WHERE volunteer_id IN ("
                + in + ") GROUP BY volunteer_id HAVING COUNT(*) > 1) t", Integer.class), "每人一张");
        List<java.util.Map<String, Object>> rows = jdbc.queryForList("SELECT volunteer_id, token, reset_count FROM user_volunteer_card WHERE volunteer_id IN (" + in + ")");
        assertEquals(rows.size(), rows.stream().map(r -> r.get("token")).distinct().count(), "令牌互不相同");
        for (java.util.Map<String, Object> r : rows) {
            Long vid = ((Number) r.get("volunteer_id")).longValue();
            assertEquals(String.valueOf(vid), cardService.verify((String) r.get("token")).getNo(), "库里的当前令牌核验得通");
            assertEquals(resets.getOrDefault(vid, new java.util.concurrent.atomic.AtomicInteger()).get(), ((Number) r.get("reset_count")).intValue(),
                    "重置次数＝成功重置的次数");
        }
        assertTrue(tally.getOrDefault("重置·成功", new java.util.concurrent.atomic.AtomicInteger()).get() > 0
                && tally.getOrDefault("核验·成功", new java.util.concurrent.atomic.AtomicInteger()).get() > 0, String.valueOf(tally));
    }

    // ================= 造数 =================

    static String tokenOf(VolunteerCardVO.Mine card) {
        String c = card.getQrContent();
        return c.startsWith(VolunteerCardService.QR_PREFIX) ? c.substring(VolunteerCardService.QR_PREFIX.length()) : c;
    }

    private Long volunteer(boolean registered, int status, String name) {
        Volunteer v = new Volunteer();
        v.setOpenid("test:card:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setRealName(registered ? name : null);
        v.setStatus(status);
        v.setManagerFlag(0);
        if (registered) {
            v.setRegisterTime(LocalDateTime.now());
        }
        volunteerMapper.insert(v);
        return v.getId();
    }

    private Long activity(int status) {
        Activity a = new Activity();
        a.setTitle("证件测试活动_" + SEQ.incrementAndGet());
        a.setStartTime(LocalDateTime.now().minusHours(3));
        a.setEndTime(LocalDateTime.now().minusHours(1));
        a.setStatus(status);
        a.setRunStatus(2);
        a.setNeedAudit(0);
        a.setMinProjects(0);
        a.setRequireMinJoinCount(0);
        a.setPointsBase(10);
        a.setLeaderMultiplier(new BigDecimal("1.4"));
        a.setManagerMultiplier(new BigDecimal("1.2"));
        activityMapper.insert(a);
        return a.getId();
    }

    private void attendance(Long activityId, Long volunteerId, int minutes, int secretaryStatus) {
        ActivitySlot s = new ActivitySlot();
        s.setActivityId(activityId);
        s.setProjectName("岗位_" + SEQ.incrementAndGet());
        s.setStartTime(LocalDateTime.now().minusHours(3));
        s.setEndTime(LocalDateTime.now().minusHours(1));
        s.setNeedCount(10);
        slotMapper.insert(s);
        ActivityAttendance att = new ActivityAttendance();
        att.setActivityId(activityId);
        att.setSlotId(s.getId());
        att.setVolunteerId(volunteerId);
        att.setCheckInTime(LocalDateTime.now().minusHours(3));
        att.setCheckOutTime(LocalDateTime.now().minusHours(1));
        att.setServiceMinutes(minutes);
        att.setAttendStatus(1);
        att.setSecretaryStatus(secretaryStatus);
        att.setPointsStatus(0);
        att.setPointsFactor(0);
        attendanceMapper.insert(att);
    }

    private static void assertMessage(String fragment, Executable call) {
        BusinessException e = assertThrows(BusinessException.class, call);
        assertTrue(e.getMessage().contains(fragment), "期望提示含「" + fragment + "」，实际：" + e.getMessage());
    }

}
