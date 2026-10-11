package cn.vv.test.mysqlmock.mybatis;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.api.ResourceLoader;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 无 Spring 的 MyBatis / MyBatis-Plus 装配助手（D-05 一等接入）。
 * <p>以引擎 DataSource 构建 SqlSessionFactory，注册注解 Mapper / 包扫描 / XML Mapper，
 * 按需挂拦截器；事务工厂为内置自动提交实现（D-14）。MyBatis-Plus 模式经
 * {@code mybatisPlus(true)} 显式开启（运行期需消费方自带 MP 依赖）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
public final class MysqlMockMybatis {

    private final MysqlMockEngine engine;
    private final String mapperPackage;
    private final List<Class<?>> mapperClasses;
    private final List<String> mapperXmlResources;
    private final List<Interceptor> interceptors;
    private final boolean mybatisPlus;
    private final ConfigurationCustomizer customizer;

    private MysqlMockMybatis(Builder builder) {
        this.engine = builder.engine;
        this.mapperPackage = builder.mapperPackage;
        this.mapperClasses = builder.mapperClasses;
        this.mapperXmlResources = builder.mapperXmlResources;
        this.interceptors = builder.interceptors;
        this.mybatisPlus = builder.mybatisPlus;
        this.customizer = builder.customizer;
    }

    public static Builder builder(MysqlMockEngine engine) {
        return new Builder(engine);
    }

    /**
     * 构建 SqlSessionFactory（引擎 DataSource + 自动提交事务 + Mapper 注册）。
     *
     * @return 会话工厂
     */
    public SqlSessionFactory buildSqlSessionFactory() {
        Configuration configuration = newConfiguration();
        Environment environment = new Environment("mysql-mock",
                new MysqlMockTransactionFactory(), engine.dataSource());
        configuration.setEnvironment(environment);
        for (Interceptor interceptor : interceptors) {
            configuration.addInterceptor(interceptor);
        }
        // 定制先于 Mapper 注册：GlobalConfig（sqlInjector / MetaObjectHandler）需在注入期生效
        if (customizer != null) {
            customizer.apply(configuration);
        }
        if (mapperPackage != null) {
            configuration.addMappers(mapperPackage);
        }
        for (Class<?> mapperClass : mapperClasses) {
            configuration.addMapper(mapperClass);
        }
        for (String resource : mapperXmlResources) {
            try (InputStream in = ResourceLoader.open(resource)) {
                new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("读取 Mapper XML 失败: " + resource, e);
            }
        }
        // MP 模式也用 DefaultSqlSessionFactory：MybatisSqlSessionFactoryBuilder 会以默认
        // GlobalConfig 覆盖定制（含 MetaObjectHandler），注入方法与 MybatisConfiguration 无关
        return new org.apache.ibatis.session.defaults.DefaultSqlSessionFactory(configuration);
    }

    private Configuration newConfiguration() {
        if (!mybatisPlus) {
            return new Configuration();
        }
        try {
            Class<?> configurationClass = Class.forName(
                    "com.baomidou.mybatisplus.core.MybatisConfiguration");
            return (Configuration) configurationClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "mybatisPlus(true) 需要 classpath 提供 MyBatis-Plus（com.baomidou:mybatis-plus-core）", e);
        }
    }

    /** Configuration 定制钩子（GlobalConfig、类型注册等）。 */
    public interface ConfigurationCustomizer {

        void apply(Configuration configuration);
    }

    /** 装配构造器。 */
    public static final class Builder {

        private final MysqlMockEngine engine;
        private String mapperPackage;
        private final List<Class<?>> mapperClasses = new ArrayList<>();
        private final List<String> mapperXmlResources = new ArrayList<>();
        private final List<Interceptor> interceptors = new ArrayList<>();
        private boolean mybatisPlus;
        private ConfigurationCustomizer customizer;

        private Builder(MysqlMockEngine engine) {
            this.engine = engine;
        }

        /** Mapper 接口包扫描。 */
        public Builder mapperPackage(String mapperPackage) {
            this.mapperPackage = mapperPackage;
            return this;
        }

        /** 单个 Mapper 接口（注解形态）。 */
        public Builder mapperClass(Class<?> mapperClass) {
            mapperClasses.add(mapperClass);
            return this;
        }

        /** XML Mapper 资源（classpath: 前缀或文件路径）。 */
        public Builder mapperXml(String resource) {
            mapperXmlResources.add(resource);
            return this;
        }

        /** 拦截器（如 MP 分页 MybatisPlusInterceptor）。 */
        public Builder interceptors(Interceptor... plugins) {
            for (Interceptor plugin : plugins) {
                interceptors.add(plugin);
            }
            return this;
        }

        /** 开启 MyBatis-Plus 模式（MybatisConfiguration + MybatisSqlSessionFactoryBuilder）。 */
        public Builder mybatisPlus(boolean enabled) {
            this.mybatisPlus = enabled;
            return this;
        }

        /** Configuration 定制钩子。 */
        public Builder customize(ConfigurationCustomizer hook) {
            this.customizer = hook;
            return this;
        }

        public MysqlMockMybatis build() {
            return new MysqlMockMybatis(this);
        }
    }
}
