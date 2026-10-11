package cn.vv.test.mysqlmock.mybatis;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.mybatis.order.OrderItem;
import cn.vv.test.mysqlmock.mybatis.order.OrderItemMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * T7 MyBatis 端到端：真实 XML 动态 SQL + resultMap 的 Mapper，useGeneratedKeys 主键回填；
 * 会话 commit/rollback 为空操作（D-14，MyBatis 层不触发 JDBC 拒绝）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
class MybatisEndToEndTest {

    private static MysqlMockEngine engine;
    private static SqlSessionFactory sessionFactory;

    @BeforeAll
    static void setUp() {
        engine = MysqlMockEngine.builder()
                .schema("classpath:mysql/orders.sql", "classpath:mysql/dine-customer.sql", "classpath:mysql/sku-unit.sql")
                .dataset("classpath:datasets/dine-case.yml")
                .build();
        sessionFactory = MysqlMockMybatis.builder(engine)
                .mapperXml("classpath:mapper/OrderItemMapper.xml")
                .build()
                .buildSqlSessionFactory();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    @Test
    void insertWithGeneratedKeysBackfill() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            OrderItemMapper mapper = session.getMapper(OrderItemMapper.class);
            OrderItem item = new OrderItem();
            item.setOrderId(99L);
            item.setSku("SKU-C");
            item.setQty(5L);
            item.setPrice(new BigDecimal("12.30"));
            assertEquals(1, mapper.insert(item));
            assertEquals(4L, item.getId(), "useGeneratedKeys 主键回填到实体字段");

            OrderItem loaded = mapper.selectById(item.getId());
            assertNotNull(loaded);
            assertEquals("SKU-C", loaded.getSku());
            assertEquals(5L, loaded.getQty());
            assertEquals(0, new BigDecimal("12.30").compareTo(loaded.getPrice()));
        }
    }

    @Test
    void dynamicSqlWithResultMap() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            OrderItemMapper mapper = session.getMapper(OrderItemMapper.class);
            List<OrderItem> byQty = mapper.selectByCondition(10L, null, null);
            assertEquals(1, byQty.size(), "qty >= 10 仅 item3");
            assertEquals("SKU-A", byQty.get(0).getSku());

            List<OrderItem> bySkuAndOrder = mapper.selectByCondition(null, "SKU-A", 1L);
            assertEquals(1, bySkuAndOrder.size());
            assertEquals(1L, bySkuAndOrder.get(0).getId());

            List<OrderItem> allConditions = mapper.selectByCondition(9L, "SKU-A", 3L);
            assertEquals(1, allConditions.size());
            assertEquals(3L, allConditions.get(0).getId());
        }
    }

    @Test
    void updateDeleteAndAggregate() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            OrderItemMapper mapper = session.getMapper(OrderItemMapper.class);
            assertEquals(1, mapper.updatePrice(2L, new BigDecimal("25.00")));
            assertEquals(0, new BigDecimal("100.00")
                    .compareTo(mapper.sumPriceByOrderId(2L)), "SUM(qty * price) 聚合");

            assertEquals(1, mapper.deleteByOrderId(2L));
            assertNull(mapper.selectById(2L));
            assertNull(mapper.sumPriceByOrderId(2L), "删除后 SUM 无行返回 NULL");
        }
    }

    @Test
    void sessionCommitAndRollbackAreNoops() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            session.commit();
            session.rollback();
            OrderItemMapper mapper = session.getMapper(OrderItemMapper.class);
            assertEquals(1, mapper.selectByCondition(null, "SKU-A", 1L).size(),
                    "commit/rollback 空操作后数据可查");
        }
    }
}
