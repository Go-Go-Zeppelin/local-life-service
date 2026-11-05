package com.hmdp.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 笔记状态。编码与 tb_blog.status、接口返回值一致。 */
@Getter
@RequiredArgsConstructor
public enum BlogState {
    DRAFT(0),
    PENDING_REVIEW(1),
    PUBLISHED(2),
    REJECTED(3);

    private final int code;

    public static BlogState fromCode(Integer code) {
        for (BlogState state : values()) {
            if (Integer.valueOf(state.code).equals(code)) {
                return state;
            }
        }
        throw new IllegalStateException("无法识别笔记状态: " + code);
    }
}
