package cn.vv.test.mysqlmock.boot;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Spring Boot 装配入口（D-04）：显式开启（mysql-mock.enabled=true）后——
 * 模式 B（创建）：测试应用没有 DataSource 时注册唯一的 mock DataSource；
 * 模式 A（接管）：由 {@link MysqlMockDataSourceReplacer} 按显式 bean 名替换。
 * 不使用 @AutoConfiguration 注解（Boot 2.7+ 才存在，D-04 兼容 2.1）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
@Configuration
@AutoConfigureBefore(DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(MysqlMockProperties.class)
@ConditionalOnProperty(prefix = "mysql-mock", name = "enabled", havingValue = "true")
public class MysqlMockAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(MysqlMockAutoConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public MysqlMockEngine mysqlMockEngine(MysqlMockProperties properties) {
        feedbackUnimplemented(properties);
        MysqlMockEngineBuilderAdapter builder = new MysqlMockEngineBuilderAdapter(properties);
        return builder.build();
    }

    /** 模式 B：仅当容器没有任何 DataSource 时注册（接管形态走 Replacer，不重复注册）。 */
    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    public DataSource mysqlMockDataSource(MysqlMockEngine engine) {
        return engine.dataSource();
    }

    /** 模式 A：BDRPP 必须静态注册（早于配置类实例化）。 */
    @Bean
    public static MysqlMockDataSourceReplacer mysqlMockDataSourceReplacer() {
        return new MysqlMockDataSourceReplacer();
    }

    private void feedbackUnimplemented(MysqlMockProperties properties) {
        if (!properties.isSqlStrict()) {
            LOG.warn("mysql-mock.sql.strict=false 未实现：引擎按 strict 执行（R9 不静默）");
        }
        for (Map.Entry<String, String> entry : properties.getCompatibility().entrySet()) {
            LOG.warn("mysql-mock.compatibility.{}={} 已登记，差分校验在 -Pcompat 管道生效（D-09）",
                    entry.getKey(), entry.getValue());
        }
    }

    /** Properties → 引擎构造器（隔离 Engine 实现细节）。 */
    static final class MysqlMockEngineBuilderAdapter {

        private final MysqlMockProperties properties;

        MysqlMockEngineBuilderAdapter(MysqlMockProperties properties) {
            this.properties = properties;
        }

        MysqlMockEngine build() {
            cn.vv.test.mysqlmock.api.MysqlMockEngineBuilder builder = MysqlMockEngine.builder()
                    .mysqlVersion(properties.getMysqlVersion());
            if (!properties.getSchema().isEmpty()) {
                builder.schema(properties.getSchema().toArray(new String[0]));
            }
            if (!properties.getDatasets().isEmpty()) {
                builder.dataset(properties.getDatasets().toArray(new String[0]));
            }
            return builder.build();
        }
    }
}
