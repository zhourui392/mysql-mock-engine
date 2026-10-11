-- 现网语料（T1）：商品中心 sql/10_create_sku_unit.sql 原文（省略 USE）。
-- 覆盖点：简写生成列 `AS (...) STORED`（无 GENERATED ALWAYS）、表级 COLLATE、ON UPDATE CURRENT_TIMESTAMP。
CREATE TABLE IF NOT EXISTS vv_pc_sku_unit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
  sku_id BIGINT NOT NULL COMMENT 'SKU（vv_pc_sku.id）',
  unit VARCHAR(32) NOT NULL COMMENT '交易单位名（瓶/箱/件）',
  ratio_to_base DECIMAL(10,3) NOT NULL COMMENT '换算比：1 单位 = N 最小单位（>0，校验限 2 位小数）',
  remark VARCHAR(128) NULL COMMENT '同名多比率的区分说明（如「6 支装」「12 支装」）',
  is_default TINYINT(1) NOT NULL DEFAULT 0 COMMENT '默认交易单位：每 SKU 恰好一行',
  default_marker BIGINT AS (IF(is_default = 1 AND delete_flag = 0, sku_id, NULL)) STORED
    COMMENT '生成列：把「每 SKU 至多一个默认」落到唯一键；「至少一个」由应用层保证（U10）',
  sort INT NOT NULL DEFAULT 0 COMMENT '展示排序（稀疏整数）',
  tenant_code VARCHAR(64) NOT NULL COMMENT '冗余：租户编码',
  created_by VARCHAR(64) NULL COMMENT '创建人',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_by VARCHAR(64) NULL COMMENT '更新人',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  delete_flag TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删标记：0 未删 1 已删',
  UNIQUE KEY uk_sku_unit_ratio (sku_id, unit, ratio_to_base),
  UNIQUE KEY uk_sku_default (default_marker),
  KEY idx_sku (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='SKU 单位换算表：一 SKU 多交易单位，允许同名多比率';
