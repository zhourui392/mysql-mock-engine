-- 现网语料（T1）：dine_customer_phone 为点销 sql/点销9.21.sql 原文；
-- dine_customer 按同文件中各 ALTER 语句整理为建表形态（occupies_unique 生成列原文保留）。
CREATE TABLE `vv_dine`.`dine_customer` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_code` VARCHAR(64) DEFAULT NULL COMMENT '租户 code',
  `shop_id` BIGINT NOT NULL COMMENT '店铺 id',
  `nickname` VARCHAR(64) DEFAULT NULL COMMENT '顾客昵称',
  `phone_dial` VARCHAR(8) NOT NULL DEFAULT '852' COMMENT '国际区号，不含+',
  `phone` VARCHAR(32) DEFAULT NULL COMMENT '本地号',
  `status` VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT 'active/deleted',
  `occupies_unique` CHAR(1) GENERATED ALWAYS AS (IF(`status` = 'active', '1', NULL)) STORED COMMENT '仅 active 占位；deleted 等同号可并存',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_phone_active` (`tenant_code`, `shop_id`, `phone_dial`, `phone`, `occupies_unique`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点销顾客（按现网整理）';

CREATE TABLE IF NOT EXISTS `vv_dine`.`dine_customer_phone` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` BIGINT NOT NULL COMMENT '店铺 id',
  `tenant_code` VARCHAR(64) DEFAULT NULL COMMENT '租户 code',
  `customer_id` BIGINT NOT NULL COMMENT '顾客 id',
  `phone_dial` VARCHAR(8) NOT NULL DEFAULT '852' COMMENT '国际区号，不含+',
  `phone` VARCHAR(32) NOT NULL COMMENT '本地号',
  `is_default` TINYINT NOT NULL DEFAULT 0 COMMENT '是否默认号 1/0',
  `occupies_unique` CHAR(1) GENERATED ALWAYS AS (IF(`released_at` IS NULL, '1', NULL)) STORED COMMENT '未释放占位',
  `default_guard` CHAR(1) GENERATED ALWAYS AS (IF(`released_at` IS NULL AND `is_default` = 1, '1', NULL)) STORED COMMENT '在用默认号占位',
  `released_at` DATETIME DEFAULT NULL COMMENT '释放时间；NULL=占用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_customer_phone_customer` (`shop_id`, `customer_id`, `released_at`),
  KEY `idx_customer_phone_lookup` (`customer_id`, `released_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点销顾客手机号（占号 SSOT）';
