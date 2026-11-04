package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("tb_blog_feed_outbox")
public class BlogFeedOutbox {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long blogId;
    private Long authorId;
    private Integer reviewRound;
    private Long publishTimeMillis;
    /** 0 待执行，1 领取中，2 已完成。 */
    private Integer status;
    private String claimToken;
    private LocalDateTime leaseUntil;
    private Integer attempts;
    private LocalDateTime nextRetryTime;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
