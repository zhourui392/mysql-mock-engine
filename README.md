# mysql-mock-engine

JVM 内存的 MySQL 模拟引擎（测试用途），JDK 8 单轨字节码。面向 dine 组 Java 8 /
Spring Boot 2.1.3 / MyBatis-Plus 3.4.3.4 服务的 **JUnit + Mockito 纯单测**场景，
替代 H2 `MODE=MySQL` 的方言差异与 Docker MySQL 的启动成本。

## 状态

阶段 1–3 完成（2026-10-11，`mvn clean install` 全绿，89 tests）；阶段 4（Spring Boot
starter、差分兼容、发布）按方案后置。

| 模块 | 内容 | 状态 |
|---|---|---|
| `mysql-mock-api` | 引擎 SPI、QueryResult/UpdateResult、Dataset、异常语义 | ✅ |
| `mysql-mock-schema` | MySQL DDL 解析（ShardingSphere 5.3.2 门禁）+ Catalog + YAML 装载 | ✅ 23 tests |
| `mysql-mock-storage` | 内存表、唯一索引、NOT NULL/自增、快照恢复 | ✅ 5 tests |
| `mysql-mock-calcite` | Calcite 1.42.0 查询引擎、自有 DML 执行器、STORED 生成列求值 | ✅ 28 tests |
| `mysql-mock-jdbc` | DataSource/Connection/ResultSet/DatabaseMetaData（`jdbc:mysql:mock:`） | ✅ 15 tests |
| `mysql-mock-mybatis` | 无 Spring 的 MyBatis/MyBatis-Plus 装配助手 | ✅ 7 tests |
| `mysql-mock-junit5` | `@MysqlMockTest` 扩展（jupiter-api 5.3.2 基线，RECREATE/SNAPSHOT 隔离） | ✅ 5 tests |
| `mysql-mock-compatibility-tests` | 消费方依赖形态的兼容/字节码/零新增门禁 | ✅ 6 tests |

## 构建与测试

```bash
mvn clean install          # JDK 8 唯一构建/测试线；全模块 + 全部测试
mvn -pl <module> test      # 单模块
```

## 用法（速览）

```java
// 原生 API
try (MysqlMockEngine engine = MysqlMockEngine.builder()
        .schema("classpath:mysql/schema.sql")
        .dataset("classpath:datasets/data.yml")
        .build()) {
    QueryResult rs = engine.query("SELECT ... WHERE id = ?", 1L);
    UpdateResult ur = engine.execute("INSERT INTO t (a, b) VALUES (?, ?)", 1, "x");
}

// JUnit 5 一等接入（含 MyBatis 装配）
@MysqlMockTest(schema = "classpath:mysql/schema.sql",
        datasets = "classpath:datasets/data.yml",
        reset = MysqlMockTest.ResetStrategy.RECREATE,
        mapperPackages = "com.vv.dine.mapper")
class OrderServiceTest {
    @Test
    void crud(SqlSessionFactory factory) { ... }   // 或注入 MysqlMockEngine / DataSource
}

// 无 Spring 装配助手（MyBatis / MyBatis-Plus）
SqlSessionFactory factory = MysqlMockMybatis.builder(engine)
        .mapperXml("classpath:mapper/OrderMapper.xml")
        .mybatisPlus(true)
        .interceptors(paginationInterceptor)
        .build()
        .buildSqlSessionFactory();
```

## 关键设计

- DDL 解析走 ShardingSphere SQL Parser 5.3.2（钉版，仅作语法门禁）；SELECT 走
  Calcite 1.42.0；INSERT/UPDATE/DELETE 全部自有执行器。
- STORED 生成列必做（`GENERATED ALWAYS AS (...) STORED` 与简写两种语法）；
  外键 / CHECK / VIRTUAL / 清单外 collation 显式拒绝。
- utf8mb4_general_ci / utf8mb4_unicode_ci：唯一键等值忽略大小写 + 尾随空格
  （SELECT 等值/GROUP BY 为 Calcite 二进制比较，见 验证记录「已知差异」）。
- 事务仅自动提交：`rollback()` / `setAutoCommit(false)` 显式抛
  `SQLFeatureNotSupportedException`；MyBatis 会话层 commit/rollback 为空操作。
- 未支持语法显式失败（strict），错误码对齐 MySQL（1062 / 1146 / 1235 等）。
- 对外工件（mybatis / junit5 模块）shade + relocate 全部第三方依赖，消费方依赖树零新增。

方案全文与决策记录（D-01～D-16）见 `docs/designs/2026-10-11-mysql-mock-engine/`；
逐项验收证据见同目录 `验证记录.md`（T0–T15）。
