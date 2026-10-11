package cn.vv.test.mysqlmock.boot;

import cn.vv.test.mysqlmock.api.DatabaseSnapshot;
import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Spring TestContext 侧的状态复位（D-06）：与 TestContext 缓存解耦——上下文跨测试类复用时，
 * 引擎状态按测试方法边界复位（RECREATE 重放 / SNAPSHOT 恢复初始化快照，快照存上下文单例，
 * 随上下文生命周期而非方法）。
 * <p>一等复位入口仍是 junit5 Extension；本 listener 供 @SpringBootTest 形态接入。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class MysqlMockTestExecutionListener implements TestExecutionListener {

    /** 上下文级快照单例名（SNAPSHOT 策略下跨方法 / 跨测试类共享同一初始化态）。 */
    static final String SNAPSHOT_SINGLETON =
            MysqlMockTestExecutionListener.class.getName() + ".initialSnapshot";

    @Override
    public void beforeTestMethod(TestContext testContext) {
        MysqlMockEngine engine = engineOf(testContext);
        if (engine == null) {
            return;
        }
        String strategy = testContext.getApplicationContext().getEnvironment()
                .getProperty("mysql-mock.reset.strategy", MysqlMockProperties.ResetStrategy.RECREATE.name());
        if (MysqlMockProperties.ResetStrategy.SNAPSHOT.name().equalsIgnoreCase(strategy)) {
            engine.restore(initialSnapshot(testContext, engine));
        } else {
            engine.reset();
        }
    }

    private DatabaseSnapshot initialSnapshot(TestContext testContext, MysqlMockEngine engine) {
        ApplicationContext context = testContext.getApplicationContext();
        ConfigurableListableBeanFactory beanFactory =
                ((ConfigurableApplicationContext) context).getBeanFactory();
        DatabaseSnapshot snapshot = (DatabaseSnapshot) beanFactory.getSingleton(SNAPSHOT_SINGLETON);
        if (snapshot == null) {
            snapshot = engine.snapshot();
            beanFactory.registerSingleton(SNAPSHOT_SINGLETON, snapshot);
        }
        return snapshot;
    }

    private MysqlMockEngine engineOf(TestContext testContext) {
        ApplicationContext context = testContext.getApplicationContext();
        if (context == null || !context.containsBean("mysqlMockEngine")) {
            return null;
        }
        return context.getBean(MysqlMockEngine.class);
    }
}
