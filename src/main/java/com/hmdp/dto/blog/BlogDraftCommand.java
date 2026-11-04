package com.hmdp.dto.blog;

import lombok.Data;

/** 完整保存草稿内容。编辑已有草稿时必须提供 expectedVersion。 */
@Data
public class BlogDraftCommand {
    private Long shopId;
    private String title;
    private String images;
    private String content;
    private Long expectedVersion;
}
