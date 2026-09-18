package com.hengde.user;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.user.dto.AddressSaveDTO;
import com.hengde.user.service.AddressService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地址管理的并发（前两条<b>必跑</b>）与压测（{@code @Tag("stress")} 那一条）。
 *
 * <p>「最多 20 条」是先数再插，并发时不串行就会越过上限——靠按人上锁（去掉锁时第二条用例红）。
 * 置顶是「先清旧的、再置新的」两条语句：第一条 UPDATE 锁住这个人的全部地址行，并发置顶本来就在行锁上排队，
 * 第一条用例钉的是「最后恰好一条置顶、没有撞键或死锁」这个结果，不是那把锁。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class AddressConcurrencyTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L + 300_000L);

    @Autowired
    private AddressService addressService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sixteenThreadsToppingDifferentAddresses_leaveExactlyOneTop() throws Exception {
        Long me = volunteer();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ids.add(addressService.create(me, address(i)));
        }
        int n = 16;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Long target = ids.get(i % ids.size());
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                addressService.top(me, target);
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1, tops(me), "并发置顶之后恰好一条置顶");
    }

    @Test
    void concurrentCreatesAtTheLimit_stopExactlyAtTwenty() throws Exception {
        Long me = volunteer();
        for (int i = 0; i < AddressService.MAX_PER_VOLUNTEER - 2; i++) {
            addressService.create(me, address(i));
        }
        int n = 8;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return addressService.create(me, address(100 + k));
            }));
        }
        int ok = 0;
        for (Future<Long> f : futures) {
            try {
                f.get(60, TimeUnit.SECONDS);
                ok++;
            } catch (ExecutionException e) {
                assertInstanceOf(BusinessException.class, e.getCause(), "只允许业务拒绝：" + e.getCause());
            }
        }
        pool.shutdown();
        assertEquals(2, ok);
        assertEquals(AddressService.MAX_PER_VOLUNTEER, jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_address WHERE volunteer_id = ? AND is_deleted = 0", Integer.class, me));
    }

    @Tag("stress")
    @Test
    void addressChurn_manyVolunteers_keepsAtMostOneTopAndTheLimit() throws Exception {
        List<Long> volunteers = new ArrayList<>();
        Map<Long, List<Long>> owned = new ConcurrentHashMap<>();
        for (int i = 0; i < 30; i++) {
            Long v = volunteer();
            volunteers.add(v);
            List<Long> ids = Collections.synchronizedList(new ArrayList<>());
            for (int k = 0; k < 5; k++) {
                ids.add(addressService.create(v, address(k)));
            }
            owned.put(v, ids);
        }
        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        Random rnd = new Random(7L);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 900; i++) {
            Long v = volunteers.get(rnd.nextInt(volunteers.size()));
            int roll = rnd.nextInt(10);
            int pick = rnd.nextInt(5);
            int seq = i;
            tasks.add(() -> {
                List<Long> ids = owned.get(v);
                Long target;
                synchronized (ids) {
                    target = ids.get(pick % ids.size());
                }
                String kind = roll < 3 ? "新增" : roll < 6 ? "置顶" : roll < 7 ? "取消置顶" : roll < 9 ? "修改" : "删除";
                try {
                    switch (kind) {
                        case "新增" -> ids.add(addressService.create(v, address(seq)));
                        case "置顶" -> addressService.top(v, target);
                        case "取消置顶" -> addressService.untop(v, target);
                        case "修改" -> addressService.update(v, target, address(seq));
                        default -> addressService.delete(v, target);
                    }
                    outcomes.computeIfAbsent(kind + "·成功", x -> new AtomicInteger()).incrementAndGet();
                } catch (BusinessException e) {
                    outcomes.computeIfAbsent(kind + "·拒绝·" + e.getMessage(), x -> new AtomicInteger()).incrementAndGet();
                } catch (Exception e) {
                    failures.add(kind + " → " + e);
                }
            });
        }
        long t0 = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable r : tasks) {
            futures.add(pool.submit(r));
        }
        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        System.out.println("==== 地址管理压测：" + tasks.size() + " 次 / 16 线程 / " + (System.currentTimeMillis() - t0)
                + " ms ====\n  " + new TreeMap<>(outcomes));
        assertTrue(failures.isEmpty(), "除业务拒绝外不应有任何异常（死锁、撞唯一键都算）：" + failures);
        for (Long v : volunteers) {
            assertTrue(tops(v) <= 1, "志愿者 " + v + " 至多一条置顶");
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM user_address WHERE volunteer_id = ? AND is_deleted = 0",
                    Integer.class, v) <= AddressService.MAX_PER_VOLUNTEER, "志愿者 " + v + " 不越过上限");
        }
    }

    private int tops(Long volunteerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_address WHERE volunteer_id = ? AND is_top = 1 AND is_deleted = 0",
                Integer.class, volunteerId);
    }

    private Long volunteer() {
        Volunteer v = new Volunteer();
        v.setOpenid("test:addr:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setStatus(0);
        v.setManagerFlag(0);
        volunteerMapper.insert(v);
        return v.getId();
    }

    private static AddressSaveDTO address(int i) {
        AddressSaveDTO d = new AddressSaveDTO();
        d.setRecvName("收件人" + i);
        d.setRecvPhone("13900000000");
        d.setRegion("广东省湛江市雷州市");
        d.setDetail("某路 " + i + " 号");
        return d;
    }
}
