package cn.vv.test.mysqlmock.compat;

/** T9：被 mock 的外部协作件（库存服务）。 */
public interface InventoryClient {

    /** 查询库存余量。 */
    long queryStock(String sku);
}
