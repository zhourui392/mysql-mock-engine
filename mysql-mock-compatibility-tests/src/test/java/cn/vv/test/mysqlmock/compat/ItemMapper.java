package cn.vv.test.mysqlmock.compat;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** T9：真实执行的 Mapper（注解形态）。 */
public interface ItemMapper {

    @Insert("INSERT INTO t_order_item (order_id, sku, qty, price) "
            + "VALUES (#{orderId}, #{sku}, #{qty}, #{price})")
    int insert(@Param("orderId") long orderId, @Param("sku") String sku,
               @Param("qty") long qty, @Param("price") java.math.BigDecimal price);

    @Select("SELECT id, order_id AS orderId, sku, qty, price FROM t_order_item "
            + "WHERE order_id = #{orderId} ORDER BY id")
    List<Map<String, Object>> selectByOrder(@Param("orderId") long orderId);

    @Select("SELECT COUNT(*) FROM t_order_item")
    long count();
}
