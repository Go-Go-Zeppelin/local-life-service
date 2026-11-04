-- 已有库在维护窗口停止旧版本写入后执行一次。新建库仅使用 hmdp.sql。
-- 先将新增字段默认设为已发布，为迁移前的历史记录赋值，再调整新记录默认值。
ALTER TABLE tb_blog
    ADD COLUMN status TINYINT NOT NULL DEFAULT 2 COMMENT '0草稿 1审核中 2已发布 3已驳回',
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN review_round INT NOT NULL DEFAULT 0,
    ADD COLUMN reviewer_id BIGINT UNSIGNED DEFAULT NULL,
    ADD COLUMN review_time DATETIME(3) DEFAULT NULL,
    ADD COLUMN reject_reason VARCHAR(500) DEFAULT NULL,
    ADD COLUMN publish_time DATETIME(3) DEFAULT NULL,
    ADD INDEX idx_blog_status_id (status, id),
    ADD INDEX idx_blog_author_status (user_id, status, id);
UPDATE tb_blog SET publish_time = create_time WHERE publish_time IS NULL;
ALTER TABLE tb_blog ALTER COLUMN status SET DEFAULT 0;
ALTER TABLE tb_user ADD COLUMN role TINYINT NOT NULL DEFAULT 0 COMMENT '0普通用户 1笔记审核员';
ALTER TABLE tb_follow ADD INDEX idx_follow_author_id (follow_user_id, id);
-- 历史笔记保留原 Feed，不生成新的发布任务，不批量重推历史内容。
CREATE TABLE tb_blog_feed_outbox (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    blog_id BIGINT UNSIGNED NOT NULL,
    author_id BIGINT UNSIGNED NOT NULL,
    review_round INT NOT NULL,
    publish_time_millis BIGINT NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0待执行 1领取中 2已完成',
    claim_token VARCHAR(36) DEFAULT NULL,
    lease_until DATETIME(3) DEFAULT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_error VARCHAR(500) DEFAULT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_blog_review_round (blog_id, review_round),
    KEY idx_feed_due (status, next_retry_time, id),
    KEY idx_feed_lease (status, lease_until, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
