package com.hmdp.action;

import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogFeedOutbox;
import com.hmdp.enums.BlogState;
import com.hmdp.enums.BlogStateEvent;
import com.hmdp.handler.BlogTransitionContext;
import com.hmdp.mapper.BlogFeedOutboxMapper;
import com.hmdp.mapper.BlogMapper;
import org.springframework.http.HttpStatus;
import org.springframework.statemachine.StateContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 状态转换的数据库动作。请求数据来自上下文，事务由调用它的 Facade 管理。 */
@Component
public class BlogStateAction {
    @Resource
    private BlogMapper blogMapper;
    @Resource
    private BlogFeedOutboxMapper outboxMapper;

    public void edit(StateContext<BlogState, BlogStateEvent> stateContext) {
        BlogTransitionContext context = getContext(stateContext);
        int updated = blogMapper.editIfUnchanged(context.getBlog(), context.getDraftCommand());
        requireUpdated(updated);
        context.completeAction();
    }

    public void submit(StateContext<BlogState, BlogStateEvent> stateContext) {
        BlogTransitionContext context = getContext(stateContext);
        Blog blog = context.getBlog();
        if (!StringUtils.hasText(blog.getTitle()) || !StringUtils.hasText(blog.getContent())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提交审核前请填写标题和正文");
        }

        int updated = blogMapper.submitIfUnchanged(blog);
        requireUpdated(updated);
        context.completeAction();
    }

    public void approve(StateContext<BlogState, BlogStateEvent> stateContext) {
        BlogTransitionContext context = getContext(stateContext);
        Blog blog = context.getBlog();
        long publishTimeMillis = System.currentTimeMillis();
        LocalDateTime publishTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(publishTimeMillis), ZoneOffset.UTC);

        // 发布状态和推送任务必须一起成功，完成标记在两次数据库写入之后设置。
        int updated = blogMapper.approveIfUnchanged(blog, context.getOperatorId(), publishTime);
        requireUpdated(updated);

        BlogFeedOutbox outbox = new BlogFeedOutbox();
        outbox.setBlogId(blog.getId());
        outbox.setAuthorId(blog.getUserId());
        outbox.setReviewRound(blog.getReviewRound());
        outbox.setPublishTimeMillis(publishTimeMillis);
        outbox.setStatus(0);
        outbox.setAttempts(0);
        if (outboxMapper.insert(outbox) != 1) {
            throw new IllegalStateException("发布事件保存失败");
        }

        context.completeAction();
    }

    public void reject(StateContext<BlogState, BlogStateEvent> stateContext) {
        BlogTransitionContext context = getContext(stateContext);
        LocalDateTime reviewTime = LocalDateTime.now(ZoneOffset.UTC);
        String reason = context.getReviewCommand().getReason().trim();

        int updated = blogMapper.rejectIfUnchanged(
                context.getBlog(), context.getOperatorId(), reviewTime, reason);
        requireUpdated(updated);
        context.completeAction();
    }

    public void onError(StateContext<BlogState, BlogStateEvent> stateContext) {
        BlogTransitionContext context = getContext(stateContext);
        context.recordFailure(stateContext.getException());
    }

    private BlogTransitionContext getContext(StateContext<BlogState, BlogStateEvent> stateContext) {
        Object value = stateContext.getMessageHeader(BlogTransitionContext.HEADER);
        if (!(value instanceof BlogTransitionContext)) {
            throw new IllegalStateException("笔记状态转换缺少请求上下文");
        }
        return (BlogTransitionContext) value;
    }

    private void requireUpdated(int updated) {
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "笔记版本或审核状态已变更，请刷新后重试");
        }
    }
}
