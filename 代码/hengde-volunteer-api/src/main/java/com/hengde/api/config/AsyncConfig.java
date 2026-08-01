package com.hengde.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步与定时调度的总开关。
 *
 * <p>{@code @EnableScheduling} 放在 api 而不是各领域模块：按项目约定，「主动改变应用行为」的配置
 * 归启动模块，领域模块只提供被扫描到的 bean。好处是领域模块的测试上下文默认不起调度线程，
 * 定时任务不会在跑用例时突然醒来改数据。</p>
 *
 * <p>当前挂在这里的定时任务：honor 的 {@code RankingSnapshotJob}（排行榜快照冻结）。</p>
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("hengde-async-");
        // CallerRunsPolicy：队列满时由提交方线程执行，不丢任务，自然产生背压
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
