package cn.vv.test.mysqlmock.compat;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * T9：被测业务服务 —— 外部协作件（InventoryClient）由 Mockito mock，
 * Mapper SQL 经引擎真执行（同测类共存形态，消费方最低线 mockito 2.23.4 + jupiter）。
 */
public class OrderService {

    private final ItemMapper itemMapper;
    private final InventoryClient inventoryClient;

    public OrderService(ItemMapper itemMapper, InventoryClient inventoryClient) {
        this.itemMapper = itemMapper;
        this.inventoryClient = inventoryClient;
    }

    /**
     * 下单：库存不足返回 0；充足则落库。
     *
     * @return 落库行数
     */
    public int placeOrder(long orderId, String sku, long qty, BigDecimal price) {
        if (inventoryClient.queryStock(sku) < qty) {
            return 0;
        }
        return itemMapper.insert(orderId, sku, qty, price);
    }

    /** 订单明细。 */
    public List<Map<String, Object>> itemsOf(long orderId) {
        return itemMapper.selectByOrder(orderId);
    }

    /** 引擎直查（断言数据状态）。 */
    public long itemCount(MysqlMockEngine engine) {
        return (Long) engine.query("SELECT COUNT(*) FROM t_order_item").rows().get(0)[0];
    }
}
