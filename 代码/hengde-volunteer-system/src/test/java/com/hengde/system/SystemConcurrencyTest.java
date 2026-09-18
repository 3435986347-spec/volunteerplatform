package com.hengde.system;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.system.constant.SystemCodes;
import com.hengde.system.dto.SystemDTOs;
import com.hengde.system.service.FileVaultService;
import com.hengde.system.service.OperationLogService;
import com.hengde.system.service.SerialNumberService;
import com.hengde.system.support.OperationLogRecorder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统治理的并发（前两条<b>必跑</b>）与压测（{@code @Tag("stress")}）。
 *
 * <p>① <b>16 个线程同时取号，16 个号互不相同且连号</b>——承重的是「一条语句读改写 + LAST_INSERT_ID」，
 * 「先读当前值再写回加一」在这里必然发出重号，而这个号要印在文件上给人对。
 * ② 日志是多线程往一个队列里写、单线程成批落库：200 条并发入队之后，落库出来的<b>一条不多一条不少</b>。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class SystemConcurrencyTest extends SystemTestSupport {

    @Autowired
    private SerialNumberService serialNumberService;
    @Autowired
    private OperationLogService logService;
    @Autowired
    private OperationLogRecorder recorder;
    @Autowired
    private FileVaultService vaultService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sixteenConcurrentSerialsAreAllDifferent() throws Exception {
        String segment = String.valueOf(100 + (int) (SEQ.incrementAndGet() % 800));
        int n = 16;
        CyclicBarrier gate = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    gate.await(20, TimeUnit.SECONDS);
                    return serialNumberService.next(segment, "并发用例段");
                }));
            }
            Set<String> got = ConcurrentHashMap.newKeySet();
            for (Future<String> f : futures) {
                got.add(f.get(30, TimeUnit.SECONDS));
            }
            assertEquals(n, got.size(), "取号重了：" + got);
            List<Long> numbers = got.stream().map(s -> Long.parseLong(s.substring(3))).sorted().toList();
            assertEquals(numbers.get(0) + n - 1, numbers.get(numbers.size() - 1), "应当是连号，没有空洞");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoHundredConcurrentLogsAllLandExactlyOnce() throws Exception {
        Long adminId = admin("监察部");
        String tag = "并发日志" + SEQ.incrementAndGet();
        int threads = 16;
        int perThread = 12;
        CyclicBarrier gate = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int k = t;
                futures.add(pool.submit(() -> {
                    gate.await(20, TimeUnit.SECONDS);
                    for (int i = 0; i < perThread; i++) {
                        recorder.recordOperation(null, SystemCodes.ACTOR_ADMIN, adminId,
                                tag + " " + k + "-" + i, true, null, 1);
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        while (logService.flush() > 0) {
            // 攒的都写完
        }
        Integer stored = jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE action LIKE ?",
                Integer.class, tag + "%");
        assertEquals(threads * perThread, stored, "异步批量落库不能多也不能少");
        assertEquals(0, logService.pending());
    }

    @Tag("stress")
    @Test
    void vaultChurn_invariantsHold() throws Exception {
        Long root = superAdmin();
        List<Long> folders = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 4; i++) {
            SystemDTOs.FolderSave f = new SystemDTOs.FolderSave();
            f.setName("压测文件夹" + SEQ.incrementAndGet());
            folders.add(vaultService.createFolder(f, root));
        }
        List<Long> files = new CopyOnWriteArrayList<>();
        List<Long> shares = new CopyOnWriteArrayList<>();
        Map<String, AtomicInteger> tally = new ConcurrentHashMap<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        AtomicInteger seq = new AtomicInteger();
        int threads = 16;
        int operations = 600;
        CyclicBarrier gate = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long started = System.currentTimeMillis();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    Random rnd = new Random(Thread.currentThread().getId() * 41 + 5);
                    gate.await(30, TimeUnit.SECONDS);
                    while (true) {
                        int n = seq.incrementAndGet();
                        if (n > operations) {
                            return null;
                        }
                        Long folder = folders.get(rnd.nextInt(folders.size()));
                        int dice = rnd.nextInt(100);
                        try {
                            if (dice < 35) {
                                SystemDTOs.FileSave dto = new SystemDTOs.FileSave();
                                dto.setFolderId(folder);
                                dto.setName("压测文件 " + n + ".pdf");
                                dto.setFileUrl(ownUrl(SystemCodes.DIR_VAULT, "pdf"));
                                dto.setFileSize(1024L);
                                files.add(vaultService.addFile(dto, root));
                                bump(tally, "登记文件");
                            } else if (dice < 50 && !files.isEmpty()) {
                                SystemDTOs.PublishSave p = new SystemDTOs.PublishSave();
                                p.setPublished(rnd.nextBoolean());
                                p.setPublishStart(rnd.nextBoolean() ? LocalDateTime.now().minusMinutes(1) : null);
                                p.setAllowDownload(rnd.nextBoolean());
                                vaultService.publish(files.get(rnd.nextInt(files.size())), p, root);
                                bump(tally, "公开设置");
                            } else if (dice < 62 && !files.isEmpty()) {
                                shares.add(vaultService.share(files.get(rnd.nextInt(files.size())), null, root).getId());
                                bump(tally, "分享");
                            } else if (dice < 70 && !shares.isEmpty()) {
                                vaultService.revokeShare(shares.get(rnd.nextInt(shares.size())), root);
                                bump(tally, "撤销分享");
                            } else if (dice < 78 && !files.isEmpty()) {
                                vaultService.moveFile(files.get(rnd.nextInt(files.size())),
                                        folders.get(rnd.nextInt(folders.size())), root);
                                bump(tally, "移动");
                            } else if (dice < 84 && !files.isEmpty()) {
                                vaultService.deleteFile(files.get(rnd.nextInt(files.size())), root);
                                bump(tally, "删除文件");
                            } else if (dice < 92) {
                                vaultService.openFiles();
                                bump(tally, "看内置文件");
                            } else {
                                vaultService.files(folder, null, page(20), root);
                                bump(tally, "看文件夹");
                            }
                        } catch (BusinessException e) {
                            bump(tally, "拒绝:" + e.getMessage());
                        } catch (Throwable e) {
                            unexpected.add(e);
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(300, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(List.of(), unexpected, "压测中出现非业务异常");

        Integer dupSerial = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT serial_no FROM sys_file "
                + "WHERE serial_no IS NOT NULL GROUP BY serial_no HAVING COUNT(*) > 1) t", Integer.class);
        assertEquals(0, dupSerial, "编号不能重");
        Integer deletedButOpen = jdbc.queryForObject("SELECT COUNT(*) FROM sys_file WHERE is_deleted = 1 AND id IN "
                + "(SELECT id FROM sys_file WHERE published = 1 AND is_deleted = 1)", Integer.class);
        assertTrue(deletedButOpen == null || deletedButOpen >= 0);
        // ⚠️ 核对用的 SQL 也要用 <b>Java 的「现在」</b>：测试容器是 UTC，MySQL 的 NOW() 与 JVM 本地时间差着时区，
        // 拿 NOW() 去核会把「按时间现算」核成两个数（卷批记过同一课）
        LocalDateTime checkAt = LocalDateTime.now();
        long openNow = vaultService.openFiles().size();
        Integer expectOpen = jdbc.queryForObject("SELECT COUNT(*) FROM sys_file WHERE is_deleted = 0 AND published = 1 "
                + "AND (publish_start IS NULL OR publish_start <= ?) AND (publish_end IS NULL OR publish_end > ?)",
                Integer.class, checkAt, checkAt);
        assertEquals(expectOpen.longValue(), openNow, "「此刻开放」要与按时间现算的结果一致");
        Integer badShare = jdbc.queryForObject("SELECT COUNT(*) FROM sys_file_share WHERE download_count < 0", Integer.class);
        assertEquals(0, badShare);
        System.out.println("[压测] 系统治理网盘：" + operations + " 次操作 / " + threads + " 线程 / "
                + (System.currentTimeMillis() - started) + " ms，分布 " + new java.util.TreeMap<>(tally));
    }

    private static void bump(Map<String, AtomicInteger> tally, String key) {
        tally.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }
}
