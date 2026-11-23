package com.hengde.donate;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 仅供测试的启动类。donate 是无 main 的库，{@code @SpringBootTest} 需配置类加载上下文。
 * 扫描 {@code com.hengde} 纳入 common/auth/activity/trade 的 bean，MapperScan 与 api 一致。
 *
 * <p>⚠️ <b>本模块的每个 {@code @SpringBootTest} 都要额外
 * {@code @Import(RedisTestcontainersConfig.class)}</b>——activity 传递引入 redisson starter，
 * {@code RedissonClient} 在 bean 创建时即连 Redis，缺容器整个上下文起不来。</p>
 *
 * <p>刻意<b>不加</b> {@code @EnableScheduling}：调度总开关在 api，
 * 测试上下文不该起定时线程去跑扫描任务，那会与用例自己造的数据相互干扰。</p>
 *
 * @author hengde
 */
@SpringBootApplication(scanBasePackages = "com.hengde")
@MapperScan("com.hengde.**.dao")
public class TestDonateApplication {
}
