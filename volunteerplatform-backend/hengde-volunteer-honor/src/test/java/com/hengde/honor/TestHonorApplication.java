package com.hengde.honor;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 仅供测试的启动类。honor 是无 main 的库，{@code @SpringBootTest} 需配置类加载上下文。
 * 扫描 {@code com.hengde} 纳入 common/auth/activity 的 bean，MapperScan 与 api 一致。
 *
 * <p>刻意<b>不加</b> {@code @EnableScheduling}——调度总开关在 api，测试上下文里不该起定时线程
 * 去跑快照生成，那会与用例自己造的数据相互干扰。定时任务的逻辑通过直接调
 * {@code RankingSnapshotJob} 的方法来测。</p>
 *
 * @author hengde
 */
@SpringBootApplication(scanBasePackages = "com.hengde")
@MapperScan("com.hengde.**.dao")
public class TestHonorApplication {
}
