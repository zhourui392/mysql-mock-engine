# mysql-mock-engine

JVM 内存的 MySQL 模拟引擎（测试用途），JDK 8 单轨字节码。面向 dine 组 Java 8 /
Spring Boot 2.1.3 / MyBatis-Plus 3.4.3.4 服务的 **JUnit + Mockito 纯单测**场景，
替代 H2 `MODE=MySQL` 的方言差异与 Docker MySQL 的启动成本。

## 状态

WIP（2026-10-11 首次提交）：

| 模块 | 内容 | 状态 |
|---|---|---|
| `mysql-mock-api` | 引擎 SPI、QueryResult/UpdateResult、Dataset、异常语义 | 已实现（编译安装通过） |
| `mysql-mock-schema` | MySQL DDL 解析（ShardingSphere 5.3.2 门禁）+ Catalog + YAML 装载 | 已实现，测试 20/20 绿 |
| `mysql-mock-storage` | 内存表、唯一索引、NOT NULL/自增、快照恢复 | 已实现，测试 5/5 绿 |
| `mysql-mock-calcite` | Calcite 1.42.0 查询引擎、自有 DML 执行器、STORED 生成列求值 | 编写中，未编译 |
| `mysql-mock-jdbc` | DataSource/Connection/ResultSet/DatabaseMetaData（`jdbc:mysql:mock:`） | 未开始 |
| `mysql-mock-mybatis` | 无 Spring 的 MyBatis/MyBatis-Plus 装配助手 | 未开始 |
| `mysql-mock-junit5` | `@MysqlMockTest` 扩展（jupiter-api 5.3.2 基线） | 未开始 |
| `mysql-mock-compatibility-tests` | 消费方依赖形态的兼容/字节码/零新增门禁 | 未开始 |

## 构建与测试

```bash
mvn clean install          # JDK 8（Zulu 1.8.0_502 验证）；全模块 + 全部测试
mvn -pl <module> test      # 单模块
```

## 关键设计

- DDL 解析走 ShardingSphere SQL Parser 5.3.2（钉版，仅作语法门禁）；SELECT 走
  Calcite 1.42.0；INSERT/UPDATE/DELETE 全部自有执行器。
- STORED 生成列必做（`GENERATED ALWAYS AS (...) STORED` 与简写两种语法）；
  外键 / CHECK / VIRTUAL / 清单外 collation 显式拒绝。
- utf8mb4_general_ci / utf8mb4_unicode_ci：等值与唯一键忽略大小写 + 尾随空格。
- 事务仅自动提交：`rollback()` / `setAutoCommit(false)` 显式抛
  `SQLFeatureNotSupportedException`。
- 未支持语法显式失败（strict），错误码对齐 MySQL（1062 / 1146 / 1235 等）。
- 对外工件（mybatis / junit5 模块）shade + relocate 全部第三方依赖，消费方依赖树零新增。

方案全文与决策记录（D-01～D-16）见 `docs/designs/2026-10-11-mysql-mock-engine/`。
