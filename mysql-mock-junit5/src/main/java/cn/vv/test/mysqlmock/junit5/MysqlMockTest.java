package cn.vv.test.mysqlmock.junit5;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 一等单测接入注解（方案 §4）：schema/dataset 装载与每方法隔离（3.9）。
 * <p>支持字段与参数注入：MysqlMockEngine / javax.sql.DataSource / SqlSessionFactory。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(MysqlMockExtension.class)
public @interface MysqlMockTest {

    /** DDL 资源位置（classpath: 或文件路径）。 */
    String[] schema() default {};

    /** YAML 数据集资源位置。 */
    String[] datasets() default {};

    /** 隔离策略（R7/D-06）：RECREATE 重放 DDL+数据集；SNAPSHOT 初始化后快照恢复。 */
    ResetStrategy reset() default ResetStrategy.RECREATE;

    /** Mapper 扫描包：声明后注入 SqlSessionFactory（无 Spring 装配助手）。 */
    String[] mapperPackages() default {};

    /** 隔离策略。 */
    enum ResetStrategy {
        RECREATE, SNAPSHOT
    }
}
