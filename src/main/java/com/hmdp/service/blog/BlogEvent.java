package com.hmdp.service.blog;

import lombok.Getter;
import java.util.EnumSet;
import java.util.Optional;

/** 轻量转换表。规则只给出目标状态，数据库执行结果由事务服务确认。 */
public enum BlogEvent {
    EDIT(BlogStatus.DRAFT, BlogStatus.DRAFT, BlogStatus.REJECTED),
    SUBMIT(BlogStatus.PENDING_REVIEW, BlogStatus.DRAFT, BlogStatus.REJECTED),
    APPROVE(BlogStatus.PUBLISHED, BlogStatus.PENDING_REVIEW),
    REJECT(BlogStatus.REJECTED, BlogStatus.PENDING_REVIEW);

    @Getter
    private final BlogStatus target;
    private final EnumSet<BlogStatus> sources;

    BlogEvent(BlogStatus target, BlogStatus firstSource, BlogStatus... otherSources) {
        this.target = target;
        this.sources = EnumSet.of(firstSource, otherSources);
    }

    public Optional<BlogStatus> targetFrom(Integer status) {
        return sources.stream().anyMatch(source -> Integer.valueOf(source.getCode()).equals(status))
                ? Optional.of(target) : Optional.empty();
    }
}
