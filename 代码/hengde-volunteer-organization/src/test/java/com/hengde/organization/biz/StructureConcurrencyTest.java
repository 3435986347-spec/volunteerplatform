package com.hengde.organization.biz;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.OrganizationStructureNodeMapper;
import com.hengde.organization.biz.entity.OrganizationStructureNode;
import com.hengde.organization.biz.service.StructureService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.hengde.organization.biz.StructureServiceTest.member;
import static com.hengde.organization.biz.StructureServiceTest.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组织架构维护的并发（前三条<b>必跑</b>）与压测（{@code @Tag("stress")}）。
 *
 * <p>「挪节点会不会成环」「删节点时还有没有人」都是先查再动，靠一把全局锁串行化。<b>别指望靠赛跑证明这把锁</b>——
 * 两次相反的挪动之间窗口只有几毫秒，去掉锁也未必撞得出来；第一条用例从测试线程直接持住那把锁，
 * 断言写操作<b>确实在等它</b>（去掉锁时这条必红），赛跑那条只是再确认一遍结果。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class StructureConcurrencyTest {

    private static final String LOCK_KEY = "lock:org-structure";
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L + 500_000L);

    @Autowired
    private StructureService structureService;
    @Autowired
    private OrganizationStructureNodeMapper nodeMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyWriteWaitsForTheStructureLock() throws Exception {
        Long root = rootId();
        Long a = structureService.createNode(node(root, "锁-A-" + SEQ.incrementAndGet(), null, 0));
        Long b = structureService.createNode(node(root, "锁-B-" + SEQ.incrementAndGet(), null, 0));
        Long vid = volunteer();
        Long m = structureService.addMember(a, member(volunteer(), "干事"));

        List<Runnable> writes = List.of(
                () -> structureService.createNode(node(a, "锁里建的", null, 0)),
                () -> structureService.updateNode(a, node(b, "锁-A", null, null)),
                () -> structureService.deleteNode(b),
                () -> structureService.addMember(b, member(vid, "部长")),
                () -> structureService.updateMember(m, updateTo(b)),
                () -> structureService.removeMember(m));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < writes.size(); i++) {
                Runnable w = writes.get(i);
                RLock lock = redissonClient.getLock(LOCK_KEY);
                lock.lock();
                Future<?> f;
                try {
                    f = pool.submit(w);
                    int idx = i;
                    assertThrows(TimeoutException.class, () -> f.get(800, TimeUnit.MILLISECONDS),
                            "第 " + idx + " 个写操作应当在等架构锁");
                } finally {
                    lock.unlock();
                }
                try {
                    f.get(10, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    assertInstanceOf(BusinessException.class, e.getCause(), "只允许业务拒绝：" + e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void oppositeMoves_neverMakeACycle() throws Exception {
        Long root = rootId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 15; round++) {
                Long a = structureService.createNode(node(root, "反向-A-" + SEQ.incrementAndGet(), null, 0));
                Long b = structureService.createNode(node(root, "反向-B-" + SEQ.incrementAndGet(), null, 0));
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> aUnderB = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    structureService.updateNode(a, node(b, "A", null, null));
                    return null;
                });
                Future<?> bUnderA = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    structureService.updateNode(b, node(a, "B", null, null));
                    return null;
                });
                int ok = 0;
                for (Future<?> f : List.of(aUnderB, bUnderA)) {
                    try {
                        f.get(30, TimeUnit.SECONDS);
                        ok++;
                    } catch (ExecutionException e) {
                        assertInstanceOf(BusinessException.class, e.getCause(), "只允许业务拒绝：" + e.getCause());
                        assertTrue(e.getCause().getMessage().contains("下级"), e.getCause().getMessage());
                    }
                }
                assertEquals(1, ok, "两次相反的挪动恰好成一次");
                assertAcyclic();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void samePersonIntoEightNodesAtOnce_landsExactlyOnce() throws Exception {
        Long root = rootId();
        Long vid = volunteer();
        int n = 8;
        List<Long> nodes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            nodes.add(structureService.createNode(node(root, "抢人-" + SEQ.incrementAndGet(), null, 0)));
        }
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<Long>> futures = new ArrayList<>();
        for (Long target : nodes) {
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return structureService.addMember(target, member(vid, "干事"));
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
        assertEquals(1, ok);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM organization_structure_member WHERE volunteer_id = ? AND is_deleted = 0", Integer.class, vid));
    }

    @Tag("stress")
    @Test
    void structureChurn_keepsTheTreeSound() throws Exception {
        Long root = rootId();
        Long sandbox = structureService.createNode(node(root, "压测沙盒-" + SEQ.incrementAndGet(), null, 0));
        List<Long> nodes = Collections.synchronizedList(new ArrayList<>(List.of(sandbox)));
        for (int i = 0; i < 12; i++) {
            nodes.add(structureService.createNode(node(sandbox, "初始-" + i, null, i)));
        }
        List<Long> people = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            people.add(volunteer());
        }
        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        Random rnd = new Random(11L);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 800; i++) {
            int roll = rnd.nextInt(12);
            int x = rnd.nextInt(1_000_000);
            int y = rnd.nextInt(1_000_000);
            Long person = people.get(rnd.nextInt(people.size()));
            int seq = i;
            tasks.add(() -> {
                Long p;
                Long q;
                synchronized (nodes) {
                    p = nodes.get(x % nodes.size());
                    q = nodes.get(y % nodes.size());
                }
                String kind = roll < 2 ? "建节点" : roll < 4 ? "挪节点" : roll < 5 ? "删节点"
                        : roll < 8 ? "放人" : roll < 10 ? "挪人" : roll < 11 ? "移出" : "看树";
                try {
                    switch (kind) {
                        case "建节点" -> nodes.add(structureService.createNode(node(p, "压-" + seq, null, 0)));
                        case "挪节点" -> structureService.updateNode(p, node(p.equals(sandbox) ? root : q, "动-" + seq, null, null));
                        case "删节点" -> {
                            if (p.equals(sandbox)) {
                                throw new BusinessException("沙盒本身不删");
                            }
                            structureService.deleteNode(p);
                        }
                        case "放人" -> structureService.addMember(p, member(person, "职位" + seq));
                        case "挪人", "移出" -> {
                            Long memberId = jdbc.query("SELECT id FROM organization_structure_member WHERE volunteer_id = ? AND is_deleted = 0",
                                    rs -> rs.next() ? rs.getLong(1) : null, person);
                            if (memberId == null) {
                                throw new BusinessException("这个人不在架构里");
                            }
                            if (kind.equals("挪人")) {
                                structureService.updateMember(memberId, updateTo(p));
                            } else {
                                structureService.removeMember(memberId);
                            }
                        }
                        default -> structureService.tree(true);
                    }
                    outcomes.computeIfAbsent(kind + "·成功", k -> new AtomicInteger()).incrementAndGet();
                } catch (BusinessException e) {
                    outcomes.computeIfAbsent(kind + "·拒绝·" + e.getMessage().replaceAll("「[^」]*」|\\d+", "…"),
                            k -> new AtomicInteger()).incrementAndGet();
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
            f.get(300, TimeUnit.SECONDS);
        }
        pool.shutdown();
        System.out.println("==== 组织架构压测：" + tasks.size() + " 次 / 16 线程 / " + (System.currentTimeMillis() - t0)
                + " ms ====\n  " + new TreeMap<>(outcomes));
        assertTrue(failures.isEmpty(), "除业务拒绝外不应有任何异常（死锁、撞唯一键、锁超时都算）：" + failures);
        assertAcyclic();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM organization_structure_node n "
                + "LEFT JOIN organization_structure_node p ON p.id = n.parent_id AND p.is_deleted = 0 "
                + "WHERE n.is_deleted = 0 AND n.parent_id IS NOT NULL AND p.id IS NULL", Integer.class), "没有挂在已删节点下面的孤儿节点");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM organization_structure_member m "
                + "LEFT JOIN organization_structure_node n ON n.id = m.node_id AND n.is_deleted = 0 "
                + "WHERE m.is_deleted = 0 AND n.id IS NULL", Integer.class), "没有人留在已删节点里");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT volunteer_id FROM organization_structure_member "
                + "WHERE is_deleted = 0 GROUP BY volunteer_id HAVING COUNT(*) > 1) t", Integer.class), "一个人至多一个位置");
    }

    // ---------------------------------------------------------------------

    /** 全库每个未删节点都能在 10 步以内走到唯一的根。 */
    private void assertAcyclic() {
        Map<Long, Long> parent = new HashMap<>();
        for (OrganizationStructureNode n : nodeMapper.selectList(null)) {
            parent.put(n.getId(), n.getParentId());
        }
        Long root = rootId();
        for (Long id : parent.keySet()) {
            Set<Long> seen = new HashSet<>();
            Long cur = id;
            while (cur != null && !cur.equals(root)) {
                assertTrue(seen.add(cur), "节点 " + id + " 往上走成了环：" + seen);
                cur = parent.get(cur);
            }
            assertEquals(root, cur, "节点 " + id + " 走不到根");
            assertTrue(seen.size() <= 9, "节点 " + id + " 深度 " + seen.size() + " 越过 10 层");
        }
    }

    private Long rootId() {
        return nodeMapper.selectOne(Wrappers.<OrganizationStructureNode>lambdaQuery()
                .isNull(OrganizationStructureNode::getParentId)).getId();
    }

    private static com.hengde.organization.biz.dto.StructureDTOs.MemberUpdate updateTo(Long nodeId) {
        com.hengde.organization.biz.dto.StructureDTOs.MemberUpdate d = new com.hengde.organization.biz.dto.StructureDTOs.MemberUpdate();
        d.setNodeId(nodeId);
        d.setPosition("挪过去的职位");
        return d;
    }

    private Long volunteer() {
        Volunteer v = new Volunteer();
        v.setOpenid("test:structure-cc:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setRealName("并发志愿者");
        v.setStatus(0);
        v.setManagerFlag(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }
}
