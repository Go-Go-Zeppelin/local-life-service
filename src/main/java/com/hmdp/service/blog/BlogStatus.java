package com.hmdp.service.blog;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BlogStatus {
    DRAFT(0), PENDING_REVIEW(1), PUBLISHED(2), REJECTED(3);

    private final int code;
}
