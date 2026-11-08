package com.hmdp.action;

import com.hmdp.annotation.StatesOnTransition;
import com.hmdp.config.BlogStateMachineConfig;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogFeedOutbox;
import com.hmdp.enums.BlogState;
import com.hmdp.handler.BlogTransitionContext;
import com.hmdp.mapper.BlogFeedOutboxMapper;
import com.hmdp.mapper.BlogMapper;
import org.springframework.http.HttpStatus;
import org.springframework.statemachine.annotation.EventHeaders;
import org.springframework.statemachine.annotation.WithStateMachine;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/** 注解关联转换与动作。回调中的异常写回请求上下文，由 Handler 抛出以回滚事务。 */
@Component
@WithStateMachine(id = BlogStateMachineConfig.MACHINE_ID)
public class BlogStateAction {
    @Resource
    private BlogMapper blogMapper;
    @Resource
    private BlogFeedOutboxMapper outboxMapper;

    @StatesOnTransition(source = {BlogState.DRAFT, BlogState.REJECTED}, target = BlogState.DRAFT)
    public void edit(@EventHeaders Map<String, Object> headers) {
        BlogTransitionContext context = getContext(headers);
        try {
            int updated = blogMapper.editIfUnchanged(context.getBlog(), context.getDraftCommand());
            requireUpdated(updated);
            context.completeAction();
        } catch (RuntimeException exception) {
            context.recordFailure(exception);
        }
    }

    @StatesOnTransition(source = {BlogState.DRAFT, BlogState.REJECTED}, target = BlogState.PENDING_REVIEW)
    public void submit(@EventHeaders Map<String, Object> headers) {
        BlogTransitionContext context = getContext(headers);
        try {
            Blog blog = context.getBlog();
            if (!StringUtils.hasText(blog.getTitle()) || !StringUtils.hasText(blog.getContent())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提交审核前请填写标题和正文");
            }

            int updated = blogMapper.submitIfUnchanged(blog);
            requireUpdated(updated);
            context.completeAction();
        } catch (RuntimeException exception) {
            context.recordFailure(exception);
        }
    }

    @StatesOnTransition(source = BlogState.PENDING_REVIEW, target = BlogState.PUBLISHED)
    public void approve(@EventHeaders Map<String, Object> headers) {
        BlogTransitionContext context = getContext(headers);
        try {
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
        } catch (RuntimeException exception) {
            context.recordFailure(exception);
        }
    }

    @StatesOnTransition(source = BlogState.PENDING_REVIEW, target = BlogState.REJECTED)
    public void reject(@EventHeaders Map<String, Object> headers) {
        BlogTransitionContext context = getContext(headers);
        try {
            LocalDateTime reviewTime = LocalDateTime.now(ZoneOffset.UTC);
            String reason = context.getReviewCommand().getReason().trim();

            int updated = blogMapper.rejectIfUnchanged(
                    context.getBlog(), context.getOperatorId(), reviewTime, reason);
            requireUpdated(updated);
            context.completeAction();
        } catch (RuntimeException exception) {
            context.recordFailure(exception);
        }
    }

    private BlogTransitionContext getContext(Map<String, Object> headers) {
        Object value = headers.get(BlogTransitionContext.HEADER);
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
