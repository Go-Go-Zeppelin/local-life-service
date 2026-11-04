package com.hmdp.service.blog;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.dto.blog.BlogDraftCommand;
import com.hmdp.dto.blog.BlogReviewCommand;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogFeedOutbox;
import com.hmdp.mapper.BlogFeedOutboxMapper;
import com.hmdp.mapper.BlogMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class BlogLifecycleService {
    @Resource private BlogMapper blogMapper;
    @Resource private BlogFeedOutboxMapper outboxMapper;

    @Transactional
    public Blog saveDraft(Long authorId, BlogDraftCommand command) {
        Blog blog = new Blog();
        applyContent(blog, command);
        blog.setUserId(authorId);
        blog.setStatus(BlogStatus.DRAFT.getCode());
        blog.setVersion(0L);
        blog.setReviewRound(0);
        blog.setLiked(0);
        blog.setComments(0);
        if (blogMapper.insert(blog) != 1) {
            throw new IllegalStateException("草稿保存失败");
        }
        return blogMapper.selectById(blog.getId());
    }

    @Transactional
    public Blog edit(Long authorId, Long id, BlogDraftCommand command) {
        Blog blog = ownedForUpdate(id, authorId);
        checkVersion(blog, command.getExpectedVersion());
        BlogStatus target = target(blog, BlogEvent.EDIT);
        applyContent(blog, command);
        UpdateWrapper<Blog> update = expected(blog)
                .set("shop_id", blog.getShopId()).set("title", blog.getTitle())
                .set("images", blog.getImages()).set("content", blog.getContent())
                .set("status", target.getCode());
        updateOrConflict(update);
        return blogMapper.selectById(id);
    }

    @Transactional
    public Blog submit(Long authorId, Long id, Long expectedVersion) {
        Blog blog = ownedForUpdate(id, authorId);
        requireVersion(expectedVersion);
        if (Integer.valueOf(BlogStatus.PENDING_REVIEW.getCode()).equals(blog.getStatus())
                && blog.getVersion() - 1 == expectedVersion) {
            return blog;
        }
        checkVersion(blog, expectedVersion);
        BlogStatus target = target(blog, BlogEvent.SUBMIT);
        if (!StringUtils.hasText(blog.getTitle()) || !StringUtils.hasText(blog.getContent())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提交审核前请填写标题和正文");
        }
        updateOrConflict(expected(blog).set("status", target.getCode())
                .set("review_round", blog.getReviewRound() + 1)
                .set("reviewer_id", null).set("review_time", null).set("reject_reason", null));
        return blogMapper.selectById(id);
    }

    @Transactional
    public Blog review(Long reviewerId, Long id, BlogReviewCommand command) {
        requireVersion(command.getExpectedVersion());
        if (command.getReviewRound() == null || command.getReviewRound() < 1 || command.getApproved() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审核轮次和审核结果不能为空");
        }
        String reason = command.getReason();
        if (!command.getApproved() && (!StringUtils.hasText(reason) || reason.length() > 500)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "驳回原因须为 1 至 500 个字符");
        }
        Blog blog = blogMapper.selectForUpdate(id);
        if (blog == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记不存在");
        }
        if (!command.getReviewRound().equals(blog.getReviewRound())) {
            throw conflict();
        }
        BlogEvent event = command.getApproved() ? BlogEvent.APPROVE : BlogEvent.REJECT;
        // 只接受同轮次、同版本决定的重放。编辑或再次提交后的旧决定不能被当作幂等成功。
        if (Integer.valueOf(event.getTarget().getCode()).equals(blog.getStatus())
                && blog.getVersion() - 1 == command.getExpectedVersion()) {
            return blog;
        }
        checkVersion(blog, command.getExpectedVersion());
        BlogStatus target = target(blog, event);
        Instant publishedAt = Instant.now();
        UpdateWrapper<Blog> update = expected(blog).set("status", target.getCode())
                .set("reviewer_id", reviewerId)
                .set("review_time", LocalDateTime.ofInstant(publishedAt, ZoneOffset.UTC))
                .set("reject_reason", command.getApproved() ? null : reason.trim());
        if (command.getApproved()) {
            update.set("publish_time", LocalDateTime.ofInstant(publishedAt, ZoneOffset.UTC));
        }
        updateOrConflict(update);
        if (command.getApproved()) {
            BlogFeedOutbox eventRecord = new BlogFeedOutbox();
            eventRecord.setBlogId(id);
            eventRecord.setAuthorId(blog.getUserId());
            eventRecord.setReviewRound(blog.getReviewRound());
            eventRecord.setPublishTimeMillis(publishedAt.toEpochMilli());
            eventRecord.setStatus(0);
            eventRecord.setAttempts(0);
            if (outboxMapper.insert(eventRecord) != 1) {
                throw new IllegalStateException("发布事件保存失败");
            }
        }
        return blogMapper.selectById(id);
    }

    private Blog ownedForUpdate(Long id, Long authorId) {
        Blog blog = blogMapper.selectForUpdate(id);
        if (blog == null || !authorId.equals(blog.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记不存在");
        }
        return blog;
    }

    private UpdateWrapper<Blog> expected(Blog blog) {
        return new UpdateWrapper<Blog>().eq("id", blog.getId()).eq("status", blog.getStatus())
                .eq("version", blog.getVersion()).eq("review_round", blog.getReviewRound())
                .set("version", blog.getVersion() + 1);
    }

    private void updateOrConflict(UpdateWrapper<Blog> update) {
        if (blogMapper.update(null, update) != 1) {
            throw conflict();
        }
    }

    private BlogStatus target(Blog blog, BlogEvent event) {
        return event.targetFrom(blog.getStatus()).orElseThrow(BlogLifecycleService::conflict);
    }

    private void checkVersion(Blog blog, Long version) {
        requireVersion(version);
        if (!version.equals(blog.getVersion())) {
            throw conflict();
        }
    }

    private void requireVersion(Long version) {
        if (version == null || version < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供有效的 expectedVersion");
        }
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "笔记版本或审核状态已变更，请刷新后重试");
    }

    private void applyContent(Blog blog, BlogDraftCommand command) {
        if (command.getShopId() == null || command.getShopId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择店铺");
        }
        blog.setShopId(command.getShopId());
        blog.setTitle(content(command.getTitle(), 255));
        blog.setImages(content(command.getImages(), 2048));
        blog.setContent(content(command.getContent(), 2048));
        if (StringUtils.hasText(blog.getImages()) && blog.getImages().split(",", -1).length > 9) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片最多 9 张");
        }
    }

    private String content(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        if (text.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "笔记内容超出长度限制");
        }
        return text;
    }
}
