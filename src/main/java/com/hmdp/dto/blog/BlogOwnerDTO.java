package com.hmdp.dto.blog;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class BlogOwnerDTO extends BlogPublicDTO {
    private Integer status;
    private Long version;
    private Integer reviewRound;
    private LocalDateTime reviewTime;
    private String rejectReason;
}
