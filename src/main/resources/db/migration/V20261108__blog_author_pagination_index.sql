-- 笔记审核发布表结构就绪的已有库执行一次。新建库已由 hmdp.sql 创建该索引。
ALTER TABLE tb_blog
    ADD INDEX idx_blog_author_status_publish_time (user_id, status, publish_time, id);
