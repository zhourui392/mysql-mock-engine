package cn.vv.test.mysqlmock.compat;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.junit5.MysqlMockTest;
import cn.vv.test.mysqlmock.mybatis.MysqlMockMybatis;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * T9 单测接入共存（R6/D-05）：jupiter + MockitoExtension 测试类中 @Mock 外部协作件返回
 * stub 值，Mapper SQL 经装配助手真执行且数据状态正确；引擎注入与隔离扩展同测类共存。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
@ExtendWith(MockitoExtension.class)
@MysqlMockTest(schema = {"classpath:mysql/orders.sql", "classpath:mysql/dine-customer.sql",
        "classpath:mysql/sku-unit.sql"},
        datasets = "classpath:datasets/dine-case.yml",
        mapperPackages = "cn.vv.test.mysqlmock.compat")
class JunitMockitoCoexistenceTest {

    @Mock
    private InventoryClient inventoryClient;

    @Test
    void mockCollaboratorAndRealMapperSql(MysqlMockEngine engine) {
        // 单 stub 按参数分发（mockito 2.23.4 最低线的稳定形态）
        when(inventoryClient.queryStock(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> "SKU-A".equals(invocation.getArgument(0)) ? 100L : 1L);

        SqlSessionFactory factory = MysqlMockMybatis.builder(engine)
                .mapperClass(ItemMapper.class)
                .build()
                .buildSqlSessionFactory();
        OrderService service = new OrderService(
                factory.openSession(true).getMapper(ItemMapper.class), inventoryClient);

        assertEquals(1, service.placeOrder(1L, "SKU-A", 2L, new BigDecimal("60.25")),
                "库存充足，SQL 真落库");
        assertEquals(0, service.placeOrder(1L, "SKU-RARE", 5L, new BigDecimal("9.99")),
                "外部 stub 返回低库存，不下单");

        assertEquals(4L, service.itemCount(engine), "初始 3 条 + 1 条新明细");
        assertEquals(2, service.itemsOf(1L).size(), "order 1 明细 = 初始 1 + 新 1");
    }

    @Test
    void mapperPackageScanInjection(SqlSessionFactory factory) {
        try (SqlSession session = factory.openSession(true)) {
            ItemMapper mapper = session.getMapper(ItemMapper.class);
            assertEquals(3L, mapper.count(), "@MysqlMockTest(mapperPackages) 注入的工厂可用");
        }
    }
}
