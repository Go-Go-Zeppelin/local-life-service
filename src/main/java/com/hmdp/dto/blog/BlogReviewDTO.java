package com.hmdp.dto.blog;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class BlogReviewDTO extends BlogOwnerDTO {
    private Long reviewerId;
}
