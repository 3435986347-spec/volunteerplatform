package com.hengde.system.service;

import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.system.constant.SystemCodes;
import com.hengde.system.dao.SysOperationLogMapper;
import com.hengde.system.entity.SysOperationLog;
import com.hengde.system.support.SystemProperties;
import com.hengde.system.vo.SystemVOs;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 操作日志（V4 系统治理批，Row 62「所有操作都要记录，避免出现信息泄露追责难题」）。
 *
 * <p><b>三条不可动摇的口径</b>（V4规划 D9）：</p>
 * <ul>
 *   <li><b>只追加</b>——没有修改与删除入口，唯一的删除路径是按保留期的定时清理；「谁删了那条日志」不该又要靠日志去查。</li>
 *   <li><b>异步批量</b>——{@link #record} 只把记录放进内存队列就返回，落库由 {@link #flush()} 成批做；
 *       队列满了<b>丢弃并记一行 WARN</b>：写日志失败是小事，让写日志把业务请求拖住才是大事。</li>
 *   <li><b>绝不影响业务</b>——落库异常在这里吞掉只记 ERROR（与「通知失败不影响业务」同一条取舍）。</li>
 * </ul>
 *
 * <p>领域模块测试上下文没有 {@code @EnableScheduling}，所以用例直接调 {@code flush()}——
 * 这反而让「记了什么」可以确定性地断言，不用靠等。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class OperationLogService {

    private final ConcurrentLinkedQueue<SysOperationLog> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();

    private SysOperationLogMapper logMapper;
    private SystemProperties properties;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setLogMapper(SysOperationLogMapper logMapper) {
        this.logMapper = logMapper;
    }

    @Autowired
    public void setProperties(SystemProperties properties) {
        this.properties = properties;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 记一条（不落库，只入队）。调用方在请求线程上，所以这里除了入队什么都不该做。 */
    public void record(SysOperationLog entry) {
        if (entry == null) {
            return;
        }
        if (entry.getCreateTime() == null) {
            entry.setCreateTime(LocalDateTime.now().withNano(0));
        }
        if (queued.get() >= properties.getLogQueueCapacity()) {
            int n = dropped.incrementAndGet();
            if (n % 100 == 1) {
                log.warn("操作日志队列已满（{}），已丢弃 {} 条——业务不受影响，但要查一下落库为什么跟不上",
                        properties.getLogQueueCapacity(), n);
            }
            return;
        }
        queue.offer(entry);
        queued.incrementAndGet();
    }

    /** 把攒下的写进库（定时任务与关停时调）。返回写了几条。 */
    public int flush() {
        List<SysOperationLog> batch = new ArrayList<>();
        SysOperationLog one;
        while (batch.size() < properties.getLogFlushBatch() && (one = queue.poll()) != null) {
            queued.decrementAndGet();
            batch.add(one);
        }
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            transactionTemplate.executeWithoutResult(s -> batch.forEach(logMapper::insert));
            return batch.size();
        } catch (RuntimeException e) {
            // 落库失败只记日志：把异常抛回去会让定时任务不断重试同一批，而业务早就返回了
            log.error("操作日志落库失败，丢弃 {} 条", batch.size(), e);
            return 0;
        }
    }

    /** 关停前把队列里的写完，免得最后那几条操作没留下痕迹。 */
    @PreDestroy
    public void flushAll() {
        while (flush() > 0) {
            // 一直写到队列空
        }
    }

    /** 还在排队的条数（压测与运维看）。 */
    public int pending() {
        return queued.get();
    }

    /** 查（Row 62「最高权限才可查看」，入口挂 system:log）。 */
    public PageResult<SystemVOs.OperationLog> search(Integer logType, Integer actorType, Long actorId, String keyword,
                                                     LocalDateTime from, LocalDateTime to, PageQuery query) {
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        long total = logMapper.countSearch(logType, actorType, actorId, kw, from, to);
        List<SysOperationLog> rows = logMapper.search(logType, actorType, actorId, kw, from, to,
                (long) (query.getPage() - 1) * query.getSize(), query.getSize());
        List<SystemVOs.OperationLog> out = new ArrayList<>(rows.size());
        for (SysOperationLog r : rows) {
            SystemVOs.OperationLog vo = new SystemVOs.OperationLog();
            vo.setId(r.getId());
            vo.setLogType(r.getLogType());
            vo.setLogTypeLabel(Integer.valueOf(SystemCodes.LOG_PAGE_VIEW).equals(r.getLogType()) ? "页面访问" : "操作");
            vo.setActorType(r.getActorType());
            vo.setActorTypeLabel(SystemCodes.actorLabel(r.getActorType()));
            vo.setActorId(r.getActorId());
            vo.setActorName(r.getActorName());
            vo.setDepartment(r.getDepartment());
            vo.setAction(r.getAction());
            vo.setMethod(r.getMethod());
            vo.setUri(r.getUri());
            vo.setQuery(r.getQuery());
            vo.setIp(r.getIp());
            vo.setSuccess(Integer.valueOf(1).equals(r.getSuccess()));
            vo.setErrorMsg(r.getErrorMsg());
            vo.setCostMs(r.getCostMs());
            vo.setCreateTime(r.getCreateTime());
            out.add(vo);
        }
        return PageResult.of(out, total, query.getPage(), query.getSize());
    }

    /** 按保留期清理（定时任务调）。返回删了几条。 */
    public int purge() {
        LocalDateTime before = LocalDateTime.now().minusDays(properties.getLogRetentionDays());
        int total = 0;
        int n;
        do {
            n = logMapper.purgeBefore(before, properties.getLogPurgeBatch());
            total += n;
        } while (n == properties.getLogPurgeBatch());
        if (total > 0) {
            log.info("操作日志清理：删除 {} 条（{} 之前）", total, before);
        }
        return total;
    }
}
