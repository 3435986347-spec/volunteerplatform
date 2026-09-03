package com.hengde.trade;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 仅供测试的启动类。trade 是无 main 的库，{@code @SpringBootTest} 需配置类加载上下文。
 *
 * <p><b>扫描范围刻意收窄到 {@code com.hengde.trade} 与 {@code com.hengde.common}</b>，
 * 不像别的模块那样扫整个 {@code com.hengde}——本模块只依赖 common，扫得更宽既没有 bean 可捡，
 * 又会让「trade 只依赖 common」这条约定在测试里变得看不出来。</p>
 *
 * @author hengde
 */
@SpringBootApplication(scanBasePackages = {"com.hengde.trade", "com.hengde.common"})
@MapperScan("com.hengde.trade.dao")
public class TestTradeApplication {
}
