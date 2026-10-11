package cn.vv.test.mysqlmock.compat.boot;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T14 Boot 2.1 starter 装配（D-04）：demo 形态 = Boot 2.1.3 + JDK 8，无任何真实库配置——
 * ①模式 B：无 DataSource 时注册 mock DataSource；②模式 A：显式 bean 名接管；
 * ③spring.factories 路径可装配；④.imports 文件存在（不做运行验证，OQ-4）；
 * ⑤TestExecutionListener 方法级复位（D-06，上下文缓存解耦）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
class Boot21AutoConfigIT {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ModeBApp {
    }

    @Nested
    @SpringBootTest(classes = ModeBApp.class, properties = {
            "mysql-mock.enabled=true",
            "mysql-mock.schema[0]=classpath:mysql/orders.sql",
            "mysql-mock.schema[1]=classpath:mysql/dine-customer.sql",
            "mysql-mock.schema[2]=classpath:mysql/sku-unit.sql",
            "mysql-mock.datasets[0]=classpath:datasets/dine-case.yml",
    })
    class ModeB {

        @Autowired
        private DataSource dataSource;

        @Autowired
        private MysqlMockEngine engine;

        @Test
        void registersMockDataSourceWhenNoneConfigured() throws Exception {
            assertNotNull(dataSource);
            try (Connection connection = dataSource.getConnection()) {
                assertEquals("jdbc:mysql:mock:", connection.getMetaData().getURL(),
                        "DataSource 为 mock 实现");
                assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM t_order")) {
                    assertTrue(rs.next());
                    assertEquals(4L, rs.getLong(1), "数据集已装载");
                }
                try (Statement update = connection.createStatement()) {
                    assertEquals(1, update.executeUpdate(
                            "INSERT INTO t_order (customer_id, amount, status) VALUES (1, 3.00, 'PAID')"));
                }
            }
        }

        @Test
        void engineBeanExposedAndCrudWorks() throws Exception {
            assertEquals(1L, engine.execute(
                    "DELETE FROM t_order WHERE status = 'CANCELLED'").affectedRows());
            assertEquals(3L, engine.query("SELECT COUNT(*) FROM t_order").rows().get(0)[0]);
        }
    }

    /** 模式 A：应用自带 legacy DataSource Bean（拒绝形态），被显式接管。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ModeAApp {

        @Bean
        public DataSource legacyDs() {
            throw new IllegalStateException("不应被实例化：已被 mysql-mock 接管");
        }
    }

    @Nested
    @SpringBootTest(classes = ModeAApp.class, properties = {
            "mysql-mock.enabled=true",
            "mysql-mock.replace-datasource-bean=legacyDs",
            "mysql-mock.schema[0]=classpath:mysql/orders.sql",
    })
    class ModeA {

        @Autowired
        private DataSource dataSource;

        @Test
        void replacesNamedDataSourceBean() throws Exception {
            try (Connection connection = dataSource.getConnection()) {
                assertEquals("jdbc:mysql:mock:", connection.getMetaData().getURL(),
                        "接管目标 Bean 已替换为 mock DataSource");
            }
        }

        @Test
        void replacedBeanNameResolvable(org.springframework.context.ApplicationContext context) {
            assertTrue(context.containsBean("legacyDs"));
            assertNotNull(context.getBean("legacyDs", DataSource.class));
        }
    }

    /** ⑤listener 复位：方法 A 写入，方法 B 回到初始（RECREATE 默认策略）。 */
    @Nested
    @SpringBootTest(classes = ModeBApp.class, properties = {
            "mysql-mock.enabled=true",
            "mysql-mock.schema[0]=classpath:mysql/orders.sql",
            "mysql-mock.schema[1]=classpath:mysql/dine-customer.sql",
            "mysql-mock.schema[2]=classpath:mysql/sku-unit.sql",
            "mysql-mock.datasets[0]=classpath:datasets/dine-case.yml",
    })
    class ListenerReset {

        @Autowired
        private MysqlMockEngine engine;

        @Test
        void methodAWrites() {
            assertEquals(4L, engine.query("SELECT COUNT(*) FROM t_order").rows().get(0)[0]);
            engine.execute("INSERT INTO t_order (customer_id, amount, status) VALUES (2, 8.00, 'PAID')");
            assertEquals(5L, engine.query("SELECT COUNT(*) FROM t_order").rows().get(0)[0]);
        }

        @Test
        void methodBSeesInitialState() {
            assertEquals(4L, engine.query("SELECT COUNT(*) FROM t_order").rows().get(0)[0],
                    "TestExecutionListener 在方法边界重放（D-06）");
        }
    }

    @Test
    void replaceUnknownBeanNameFailsFast() {
        org.springframework.context.annotation.AnnotationConfigApplicationContext context =
                new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        java.util.Map<String, Object> props = new java.util.HashMap<String, Object>();
        props.put("mysql-mock.enabled", "true");
        props.put("mysql-mock.replace-datasource-bean", "ghostDs");
        props.put("mysql-mock.schema[0]", "classpath:mysql/orders.sql");
        context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("test", props));
        context.register(ModeBApp.class);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> context.refresh());
        Throwable cause = e;
        boolean mentionsTarget = false;
        while (cause != null) {
            if (cause.getMessage() != null && cause.getMessage().contains("ghostDs")) {
                mentionsTarget = true;
                break;
            }
            cause = cause.getCause();
        }
        assertTrue(mentionsTarget, "报错指向不存在的接管目标 Bean 名，实际: " + e.getMessage());
    }

    @Test
    void springFactoriesRegistersAutoConfigurationAndListener() throws Exception {
        String content = read("META-INF/spring.factories");
        assertTrue(content.contains("cn.vv.test.mysqlmock.boot.MysqlMockAutoConfiguration"),
                "spring.factories 注册 AutoConfiguration（Boot 2.1-2.6 轨）");
        assertTrue(content.contains("cn.vv.test.mysqlmock.boot.MysqlMockTestExecutionListener"),
                "spring.factories 注册 TestExecutionListener");
    }

    @Test
    void autoConfigurationImportsFileShipped() throws Exception {
        String content = read(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertTrue(content.contains("MysqlMockAutoConfiguration"),
                ".imports 随包发布（Boot 2.7+/3.x 轨，不做运行验证）");
        assertFalse(content.contains("TestExecutionListener"), "listener 只走 spring.factories");
    }

    private static String read(String resource) throws Exception {
        try (InputStream in = Boot21AutoConfigIT.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "缺少资源: " + resource);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        }
    }
}
