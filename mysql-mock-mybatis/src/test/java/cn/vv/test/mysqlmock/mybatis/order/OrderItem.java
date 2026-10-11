package cn.vv.test.mysqlmock.mybatis.order;

import java.math.BigDecimal;

/** t_order_item 行（T7 resultMap 目标）。 */
public class OrderItem {

    private Long id;
    private Long orderId;
    private String sku;
    private Long qty;
    private BigDecimal price;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public Long getQty() {
        return qty;
    }

    public void setQty(Long qty) {
        this.qty = qty;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    @Override
    public String toString() {
        return "OrderItem{id=" + id + ", orderId=" + orderId + ", sku='" + sku
                + "', qty=" + qty + ", price=" + price + '}';
    }
}
