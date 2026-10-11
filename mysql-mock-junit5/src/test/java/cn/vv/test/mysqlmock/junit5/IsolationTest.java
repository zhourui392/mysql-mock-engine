package cn.vv.test.mysqlmock.junit5;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.junit5.MysqlMockTest.ResetStrategy;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T10 隔离（R7/D-06）：同一 jupiter 扩展生命周期（引擎实例复用）下两个测试方法，
 * A 插入后 B 计数回到初始值；自增指针复位。RECREATE 与 SNAPSHOT 分别验证。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
class IsolationTest {

    private static long countOrders(MysqlMockEngine engine) {
        return (Long) engine.query("SELECT COUNT(*) FROM t_order").rows().get(0)[0];
    }

    private static long insertAndReturnKey(MysqlMockEngine engine) {
        return (Long) engine.execute(
                "INSERT INTO t_order (customer_id, amount, status) VALUES (1, 10.00, 'PAID')")
                .generatedKeys().get(0);
    }

    @Nested
    @MysqlMockTest(schema = {"classpath:mysql/orders.sql", "classpath:mysql/dine-customer.sql",
            "classpath:mysql/sku-unit.sql"},
            datasets = "classpath:datasets/dine-case.yml",
            reset = ResetStrategy.RECREATE)
    class RecreateStrategy {

        @Test
        void methodAInsertsRow(MysqlMockEngine engine) {
            assertEquals(4L, countOrders(engine));
            assertEquals(5L, insertAndReturnKey(engine), "初始自增下一号 5");
            assertEquals(5L, countOrders(engine));
        }

        @Test
        void methodBSeesInitialStateAndAutoIncrementReset(MysqlMockEngine engine) {
            assertEquals(4L, countOrders(engine), "A 的插入被 RECREATE 重放隔离");
            assertEquals(5L, insertAndReturnKey(engine), "自增指针复位到初始装载位");
        }
    }

    @Nested
    @MysqlMockTest(schema = {"classpath:mysql/orders.sql", "classpath:mysql/dine-customer.sql",
            "classpath:mysql/sku-unit.sql"},
            datasets = "classpath:datasets/dine-case.yml",
            reset = ResetStrategy.SNAPSHOT)
    class SnapshotStrategy {

        @Test
        void methodADirtiesData(MysqlMockEngine engine) {
            assertEquals(4L, countOrders(engine));
            insertAndReturnKey(engine);
            insertAndReturnKey(engine);
            assertEquals(6L, countOrders(engine));
        }

        @Test
        void methodBRestoredFromSnapshot(MysqlMockEngine engine) {
            assertEquals(4L, countOrders(engine), "A 的两行插入被快照恢复消除");
            assertEquals(5L, insertAndReturnKey(engine), "自增指针随快照复位");
        }
    }

    @Nested
    @MysqlMockTest(schema = {"classpath:mysql/orders.sql", "classpath:mysql/dine-customer.sql",
            "classpath:mysql/sku-unit.sql"},
            datasets = "classpath:datasets/dine-case.yml")
    class FieldAndParameterInjection {

        @Test
        void dataSourceFieldInjected(DataSource dataSource) throws Exception {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM t_order_item")) {
                rs.next();
                assertEquals(3L, rs.getLong(1));
            }
        }
    }
}
