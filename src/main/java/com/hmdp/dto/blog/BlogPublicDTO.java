package com.hmdp.dto.blog;

import lombok.Data;
import java.time.LocalDateTime;

/** 对外展示字段，不包含审核身份和内部并发控制信息。 */
@Data
public class BlogPublicDTO {
    private Long id;
    private Long shopId;
    private Long userId;
    private String title;
    private String images;
    private String content;
    private Integer liked;
    private Integer comments;
    private String name;
    private String icon;
    private Boolean isLike;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime publishTime;
}
