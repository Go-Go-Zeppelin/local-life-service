package com.hmdp.handler;

import com.hmdp.dto.blog.BlogDraftCommand;
import com.hmdp.dto.blog.BlogReviewCommand;
import com.hmdp.entity.Blog;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/** 单次事件的数据与执行结果。每次请求创建，动作执行完后由 Handler 检查。 */
@Getter
@RequiredArgsConstructor
public class BlogTransitionContext {
    public static final String HEADER = "blogTransitionContext";

    private final Blog blog;
    private final Long operatorId;

    @Setter
    private BlogDraftCommand draftCommand;
    @Setter
    private BlogReviewCommand reviewCommand;

    private Exception failure;
    private boolean actionCompleted;

    public void completeAction() {
        actionCompleted = true;
    }

    public void recordFailure(Exception exception) {
        failure = exception;
    }
}
