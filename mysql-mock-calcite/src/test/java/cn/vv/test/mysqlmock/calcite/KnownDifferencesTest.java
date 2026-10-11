package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 已知差异现状锁定（验证记录「实现修订」12/D-16）：清单内 _ci collation 的
 * 唯一键 / 存储侧归一已实现；SELECT 等值与 GROUP BY 走 Calcite 二进制比较——
 * 本类把当前行为锚进回归网，实现 _ci 查询侧语义时须同步更新这里与差分语料。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
class KnownDifferencesTest {

    private MysqlMockEngine engine;

    @BeforeEach
    void setUp() {
        engine = MysqlMockEngine.builder()
                .schema("classpath:mysql/orders.sql",
                        "classpath:mysql/dine-customer.sql",
                        "classpath:mysql/sku-unit.sql")
                .dataset("classpath:datasets/dine-case.yml")
                .build();
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void querySideStringEqualityIsBinaryCaseSensitive() {
        // 存储侧唯一键：'Box' 与 'box' 冲突（InsertExecutionTest 已覆盖）
        // 查询侧等值：二进制比较，大小写变体不命中
        assertEquals(1L, engine.query(
                "SELECT COUNT(*) FROM dine_customer WHERE nickname = 'Alex'").rows().get(0)[0]);
        assertEquals(0L, engine.query(
                "SELECT COUNT(*) FROM dine_customer WHERE nickname = 'alex'").rows().get(0)[0],
                "已知差异：查询侧 _ci 等值未归一（Calcite 二进制比较）");
    }

    @Test
    void groupByKeepsCaseVariantsSeparate() {
        engine.executeDdl("CREATE TABLE `t_case` (`id` BIGINT NOT NULL AUTO_INCREMENT,"
                + "`tag` VARCHAR(32) NULL, PRIMARY KEY (`id`))");
        engine.execute("INSERT INTO t_case (tag) VALUES ('Paid'), ('paid'), ('PAID'), (NULL)");
        // MySQL utf8mb4_general_ci 下三组归一为 1 组 + NULL 组；引擎当前 3 组 + NULL 组
        Object groups = engine.query(
                "SELECT tag, COUNT(*) FROM t_case GROUP BY tag ORDER BY tag").rows().size();
        assertEquals(4, groups, "已知差异：GROUP BY 不合并大小写变体（3 变体 + NULL）");
    }

    @Test
    void padSpaceAlsoDiffersOnQuerySide() {
        // PAD SPACE：唯一键侧 'Box' 与 'Box ' 冲突；查询侧精确匹配不忽略尾随空格
        assertEquals(0L, engine.query(
                "SELECT COUNT(*) FROM dine_customer WHERE nickname = 'Alex '").rows().get(0)[0],
                "已知差异：查询侧 PAD SPACE 未归一");
    }
}
