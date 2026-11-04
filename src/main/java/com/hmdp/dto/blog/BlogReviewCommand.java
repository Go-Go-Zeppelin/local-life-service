package com.hmdp.dto.blog;

import lombok.Data;

@Data
public class BlogReviewCommand {
    private Long expectedVersion;
    private Integer reviewRound;
    private Boolean approved;
    private String reason;
}
