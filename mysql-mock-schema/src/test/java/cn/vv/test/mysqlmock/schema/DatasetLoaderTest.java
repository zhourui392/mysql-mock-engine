package cn.vv.test.mysqlmock.schema;

import cn.vv.test.mysqlmock.api.Dataset;
import cn.vv.test.mysqlmock.api.ResourceLoader;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** T3：数据集装载与类型转换（金额 BigDecimal、时间毫秒精度、整型字符串默认值）。 */
class DatasetLoaderTest {

    private TableDefinition loadSkuUnit() {
        List<String> statements = SqlScriptSplitter.split(ResourceLoader.readAsString("classpath:mysql/sku-unit.sql"));
        return new MySqlDdlParser().parse(statements.get(0));
    }

    @Test
    void loadYamlDataset() {
        Dataset dataset = new DatasetLoader().load(ResourceLoader.open("classpath:datasets/order-case.yml"));
        assertEquals(2, dataset.tables().size());
        List<Map<String, Object>> customers = dataset.tables().get("dine_customer");
        assertEquals(2, customers.size());
        assertEquals("Alex", customers.get(0).get("nickname"));
    }

    @Test
    void coerceDecimalKeepsScale() {
        TableDefinition skuUnit = loadSkuUnit();
        Object coerced = MysqlValueCoercer.coerce(skuUnit.column("ratio_to_base"), "100.000");
        assertInstanceOf(BigDecimal.class, coerced);
        assertEquals(new BigDecimal("100.000"), coerced);
        assertEquals(3, ((BigDecimal) coerced).scale());
    }

    @Test
    void coerceDatetimeKeepsMillisecondPrecision() {
        TableDefinition skuUnit = loadSkuUnit();
        Timestamp coerced = (Timestamp) MysqlValueCoercer.coerce(skuUnit.column("created_at"),
                "2026-01-02 03:04:05.678");
        assertEquals(Timestamp.valueOf("2026-01-02 03:04:05.678"), coerced);
        assertEquals(678, coerced.getNanos() / 1_000_000);
    }

    @Test
    void coerceIntToVarcharDefault() {
        TableDefinition skuUnit = loadSkuUnit();
        Object coerced = MysqlValueCoercer.coerce(skuUnit.column("tenant_code"), 852);
        assertEquals("852", coerced);
    }

    @Test
    void coerceTinyintAndBool() {
        TableDefinition skuUnit = loadSkuUnit();
        assertEquals(1L, MysqlValueCoercer.coerce(skuUnit.column("is_default"), true));
        assertEquals(0L, MysqlValueCoercer.coerce(skuUnit.column("is_default"), "0"));
    }

    @Test
    void rejectInvalidDatasetTopLevel() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatasetLoader().load(new java.io.ByteArrayInputStream("- a\n- b".getBytes())));
    }
}
