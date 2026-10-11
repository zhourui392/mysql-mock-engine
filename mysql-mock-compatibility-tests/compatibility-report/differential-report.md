# 差分兼容报告（D-09）

- 结果: PASS 20 / FAIL 0

- 基准: MySQL 9.5.0（docker 本地镜像；CI 钉 mysql:8.0 复跑）
- 基准参数: utf8mb4 / utf8mb4_general_ci，server 默认 sql_mode，时区 Asia/Shanghai
- 引擎: mysql-mock 0.1.0（Calcite 1.42.0）

- PASS T4-GENCOL
- PASS ODKU-INSERT (engine=1 mysql=1)
- PASS ODKU-UPDATE-VALUES (engine=2 mysql=2)
- PASS ODKU-UNCHANGED (engine=1 mysql=1)
- PASS ODKU-STATE
- PASS REPLACE-PLAIN (engine=1 mysql=1)
- PASS REPLACE-CONFLICT (engine=2 mysql=2)
- PASS REPLACE-STATE
- PASS INSERT-SELECT (engine=1 mysql=1)
- PASS INSERT-SELECT-STATE
- PASS RESET-CHECK
- PASS ERR-1364
- PASS ERR-1048
- PASS T4-JOIN
- PASS T4-NOORDER
- PASS T6-UPDATE (engine=1 mysql=1)
- PASS T6-UPDATE-AFTER
- PASS T4-DATETIME
- PASS T6-DELETE (engine=2 mysql=2)
- PASS ERR-1062 (engine=1062 mysql=1062)
