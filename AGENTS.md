# AGENTS.md

`mysql-mock-engine`：JVM 内存 MySQL 模拟引擎（测试用途），JDK 8 单轨字节码。方案与决策见
`docs/designs/2026-10-11-mysql-mock-engine/`（两文件 + 评审记录，已过审；D-01～D-16；2026-10-11
自 ai-flow-workspace 迁入本仓）。

## 构建与测试

```bash
mvn clean install          # JDK 8（Zulu 1.8.0_502 验证）；全模块 + 全部测试
mvn -pl <module> test      # 单模块
```

- JDK 8 是唯一构建与测试线（D-01）；CI 兼容矩阵按消费方（Boot 2.1.3）裁剪，不做 JDK 11/17 投入。
- 不自行 `mvn deploy`。

## 架构关键决策（改动前先对照方案）

- DDL 解析走 ShardingSphere SQL Parser 5.3.2（钉版）；SELECT 查询走 Calcite 1.42.0；INSERT/UPDATE/DELETE 全部自有执行器（Calcite 表修改通道不可用，D-03）。
- 对外工件（`mysql-mock-mybatis`、`mysql-mock-junit5`）shade + relocate 全部第三方依赖，消费方依赖树零新增（D-12）；勿在外层工件 pom 增加非 provided 的外部依赖。
- STORED 生成列必做（显式与简写两种语法）、外键 / CHECK / VIRTUAL 生成列 / 清单外 collation 显式拒绝（D-13 / D-16）。
- 事务仅自动提交：rollback / setAutoCommit(false) 显式抛 SQLFeatureNotSupportedException（D-14）。
- 一等接入是 JUnit 5 扩展 + 无 Spring 的 MyBatis 装配助手；junit5 模块按 junit-jupiter-api 5.3.2 基线编译（D-05）。
- 未支持语法显式失败（strict），禁止静默返回空结果（R9）。

## 测试口径

- 兼容/消费方视角测试在 `mysql-mock-compatibility-tests`（消费方依赖形态：jupiter 5.10.2 + mockito 2.23.4 + MyBatis-Plus 3.4.3.4）。
- 语料来自现网 DDL（dine_customer_phone / vv_pc_sku_unit 等），见各模块 test/resources。
- 验证记录落方案目录 `验证记录.md`，按 T0–T15 对应。
