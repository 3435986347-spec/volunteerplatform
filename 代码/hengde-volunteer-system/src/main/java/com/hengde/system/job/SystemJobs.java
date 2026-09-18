package com.hengde.system.job;

import com.hengde.system.service.OperationLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 系统治理的两个定时任务（触发层，不含业务逻辑；调度总开关 `@EnableScheduling` 在 api）。
 *
 * <p><b>落库任务跑得勤、清理任务一天一次</b>：前者只是把内存里攒的写进库（默认 2 秒一次，
 * 停机时还有 {@code @PreDestroy} 兜底），后者是日志唯一的删除路径（V4规划 Q8 保留 180 天）。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class SystemJobs {

    private OperationLogService operationLogService;

    @Autowired
    public void setOperationLogService(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @Scheduled(fixedDelayString = "${hengde.system.log-flush-delay-ms:2000}")
    public void flushLogs() {
        try {
            operationLogService.flush();
        } catch (RuntimeException e) {
            // 日志任务自己出错也不能往外抛：调度线程挂了之后，后面所有的日志都会静默丢掉
            log.error("操作日志落库任务异常", e);
        }
    }

    @Scheduled(cron = "${hengde.system.log-purge-cron:0 30 3 * * ?}")
    public void purgeLogs() {
        try {
            operationLogService.purge();
        } catch (RuntimeException e) {
            log.error("操作日志清理任务异常", e);
        }
    }
}
