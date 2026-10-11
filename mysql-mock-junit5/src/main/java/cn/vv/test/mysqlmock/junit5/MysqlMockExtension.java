package cn.vv.test.mysqlmock.junit5;

import cn.vv.test.mysqlmock.api.DatabaseSnapshot;
import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.mybatis.MysqlMockMybatis;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * 引擎生命周期扩展（方案 §3.7/§3.9）：beforeAll 装载 schema/dataset（并按需建 SqlSessionFactory）；
 * beforeEach 按策略恢复（RECREATE 重放 / SNAPSHOT 恢复快照，均复位自增）并注入实例字段；
 * 引擎随 Store 关闭（afterAll）。注入面：MysqlMockEngine / DataSource / SqlSessionFactory。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
public final class MysqlMockExtension implements BeforeAllCallback, AfterAllCallback,
        BeforeEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(MysqlMockExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        MysqlMockTest annotation = context.getRequiredTestClass().getAnnotation(MysqlMockTest.class);
        if (annotation == null) {
            return;
        }
        cn.vv.test.mysqlmock.api.MysqlMockEngineBuilder builder = MysqlMockEngine.builder();
        if (annotation.schema().length > 0) {
            builder.schema(annotation.schema());
        }
        if (annotation.datasets().length > 0) {
            builder.dataset(annotation.datasets());
        }
        MysqlMockEngine engine = builder.build();
        context.getStore(NAMESPACE).put(ENGINE_KEY, new CloseableEngine(engine));
        if (annotation.reset() == MysqlMockTest.ResetStrategy.SNAPSHOT) {
            context.getStore(NAMESPACE).put(SNAPSHOT_KEY, engine.snapshot());
        }
        if (annotation.mapperPackages().length > 0) {
            MysqlMockMybatis.Builder mybatis = MysqlMockMybatis.builder(engine);
            for (String mapperPackage : annotation.mapperPackages()) {
                mybatis.mapperPackage(mapperPackage);
            }
            context.getStore(NAMESPACE).put(FACTORY_KEY,
                    mybatis.build().buildSqlSessionFactory());
        }
        injectStaticFields(context, engine);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        MysqlMockTest annotation = context.getRequiredTestClass().getAnnotation(MysqlMockTest.class);
        if (annotation == null) {
            return;
        }
        MysqlMockEngine engine = engine(context);
        if (engine == null) {
            return;
        }
        if (annotation.reset() == MysqlMockTest.ResetStrategy.SNAPSHOT) {
            DatabaseSnapshot snapshot = (DatabaseSnapshot) context.getStore(NAMESPACE).get(SNAPSHOT_KEY);
            if (snapshot != null) {
                engine.restore(snapshot);
            }
        } else {
            engine.reset();
        }
        injectInstanceFields(context, engine);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        // 引擎经 Store 的 CloseableEngine 关闭
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        return type == MysqlMockEngine.class || type == DataSource.class || type == SqlSessionFactory.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        MysqlMockEngine engine = engine(extensionContext);
        if (engine == null) {
            throw new ParameterResolutionException("引擎未初始化（缺 @MysqlMockTest 注解？）");
        }
        Class<?> type = parameterContext.getParameter().getType();
        if (type == MysqlMockEngine.class) {
            return engine;
        }
        if (type == DataSource.class) {
            return engine.dataSource();
        }
        SqlSessionFactory factory = (SqlSessionFactory) extensionContext.getStore(NAMESPACE).get(FACTORY_KEY);
        if (factory == null) {
            throw new ParameterResolutionException("SqlSessionFactory 注入需在 @MysqlMockTest 声明 mapperPackages");
        }
        return factory;
    }

    private static final String ENGINE_KEY = "engine";
    private static final String SNAPSHOT_KEY = "snapshot";
    private static final String FACTORY_KEY = "sqlSessionFactory";

    private MysqlMockEngine engine(ExtensionContext context) {
        CloseableEngine holder = (CloseableEngine) context.getStore(NAMESPACE).get(ENGINE_KEY);
        return holder == null ? null : holder.engine();
    }

    private void injectStaticFields(ExtensionContext context, MysqlMockEngine engine) {
        injectFields(context, engine, null);
    }

    private void injectInstanceFields(ExtensionContext context, MysqlMockEngine engine) {
        Object instance = context.getTestInstance().orElse(null);
        if (instance != null) {
            injectFields(context, engine, instance);
        }
    }

    private void injectFields(ExtensionContext context, MysqlMockEngine engine, Object instance) {
        for (Class<?> type = context.getRequiredTestClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                boolean isStatic = Modifier.isStatic(field.getModifiers());
                if ((instance == null) != isStatic) {
                    continue;
                }
                Object value = fieldValue(context, field.getType(), engine);
                if (value == null) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    field.set(instance, value);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("注入字段失败: " + field.getName(), e);
                }
            }
        }
    }

    private Object fieldValue(ExtensionContext context, Class<?> fieldType, MysqlMockEngine engine) {
        if (fieldType == MysqlMockEngine.class) {
            return engine;
        }
        if (fieldType == DataSource.class) {
            return engine.dataSource();
        }
        if (fieldType == SqlSessionFactory.class) {
            return context.getStore(NAMESPACE).get(FACTORY_KEY);
        }
        return null;
    }

    /** 引擎包装：Store 关闭时关闭引擎。 */
    private static final class CloseableEngine implements ExtensionContext.Store.CloseableResource {

        private final MysqlMockEngine engine;

        CloseableEngine(MysqlMockEngine engine) {
            this.engine = engine;
        }

        MysqlMockEngine engine() {
            return engine;
        }

        @Override
        public void close() {
            engine.close();
        }
    }
}
