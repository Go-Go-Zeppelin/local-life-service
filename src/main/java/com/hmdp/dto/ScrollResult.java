package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
    /** Redis 游标之后是否仍有记录，空列表也可能需要继续扫描。 */
    private boolean hasMore;
}
