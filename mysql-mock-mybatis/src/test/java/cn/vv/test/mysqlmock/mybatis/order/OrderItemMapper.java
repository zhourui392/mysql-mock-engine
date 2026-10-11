package cn.vv.test.mysqlmock.mybatis.order;

import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/** T7：真实 XML 动态 SQL + resultMap 的 Mapper。 */
public interface OrderItemMapper {

    int insert(OrderItem item);

    OrderItem selectById(@Param("id") Long id);

    List<OrderItem> selectByCondition(@Param("minQty") Long minQty,
                                      @Param("sku") String sku,
                                      @Param("orderId") Long orderId);

    int updatePrice(@Param("id") Long id, @Param("price") BigDecimal price);

    int deleteByOrderId(@Param("orderId") Long orderId);

    BigDecimal sumPriceByOrderId(@Param("orderId") Long orderId);
}
