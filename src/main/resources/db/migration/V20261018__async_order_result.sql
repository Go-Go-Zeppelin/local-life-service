-- 已有数据库升级使用。新建数据库已在 hmdp.sql 包含此字段，无需重复执行。
ALTER TABLE tb_voucher_order
    ADD COLUMN redis_stock_released TINYINT(1) NOT NULL DEFAULT 1 COMMENT '关单 Redis 库存释放确认标记',
    ADD INDEX idx_order_stock_release (status, redis_stock_released, id);
ALTER TABLE tb_voucher_order ALTER COLUMN redis_stock_released SET DEFAULT 0;
-- 历史行以 1 初始化，避免迁移后对未知是否已回补的历史关单重复补库存。
-- 历史已取消订单应单独对账，只有确认未回补的记录才改为 0 交给任务处理。
