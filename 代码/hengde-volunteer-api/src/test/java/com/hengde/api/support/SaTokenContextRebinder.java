package com.hengde.api.support;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.stp.StpInterface;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * 测试专用：每个测试类开始前，把 Sa-Token 的<b>权限数据源</b>与<b>令牌存储</b>重新指向当前这个测试上下文的 bean。
 *
 * <p><b>为什么需要</b>：Sa-Token 把 {@link StpInterface} / {@link SaTokenDao} 放在 JVM 级静态的 {@link SaManager} 里，
 * 由最后一个启动的 Spring 上下文写入；而本模块的 HTTP 用例按 {@code properties} 不同会起好几个上下文，每个上下文各有自己的
 * MySQL / Redis 容器（{@code TestcontainersConfig} 是 {@code @Bean}）。一旦复用了<b>更早缓存</b>的上下文，
 * 它的 Tomcat 查权限时走的却是<b>最后启动</b>那个上下文的库——在这个库里建的子账号与授权在那个库里找不到，一律 403。
 * 全量跑 api 模块时 {@code ExamApiFlowTest} / {@code SocialApiFlowTest} / {@code SocialGovApiFlowTest} 当场撞出（单独跑都是绿的）。
 * 生产只有一个上下文，不受影响。</p>
 *
 * <p>登记在 {@code src/test/resources/META-INF/spring.factories}，对本模块全部 Spring 测试生效。</p>
 *
 * @author hengde
 */
public class SaTokenContextRebinder implements TestExecutionListener, Ordered {

    @Override
    public void prepareTestInstance(TestContext testContext) {
        rebind(testContext);
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        rebind(testContext);
    }

    private static void rebind(TestContext testContext) {
        ApplicationContext ctx = testContext.getApplicationContext();
        StpInterface stp = ctx.getBeanProvider(StpInterface.class).getIfUnique();
        if (stp != null && SaManager.getStpInterface() != stp) {
            SaManager.setStpInterface(stp);
        }
        SaTokenDao dao = ctx.getBeanProvider(SaTokenDao.class).getIfUnique();
        if (dao != null && SaManager.getSaTokenDao() != dao) {
            SaManager.setSaTokenDao(dao);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
