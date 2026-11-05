package com.hmdp.service.blog;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.blog.BlogDraftCommand;
import com.hmdp.dto.blog.BlogOwnerDTO;
import com.hmdp.dto.blog.BlogReviewCommand;
import com.hmdp.dto.blog.BlogReviewDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.enums.BlogState;
import com.hmdp.enums.BlogStateEvent;
import com.hmdp.handler.BlogStateHandler;
import com.hmdp.handler.BlogTransitionContext;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.UserHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.Resource;

/** 应用门面。身份、幂等和事务在这里处理，状态转换交给 BlogStateHandler。 */
@Service
public class BlogPublishFacade {
    @Resource
    private BlogStateHandler stateHandler;
    @Resource
    private BlogMapper blogMapper;
    @Resource
    private IBlogService queries;
    @Resource
    private UserMapper userMapper;

    @Transactional
    public Result saveDraft(BlogDraftCommand command) {
        Long authorId = userId();
        prepareContent(command);

        Blog blog = new Blog();
        blog.setUserId(authorId);
        blog.setShopId(command.getShopId());
        blog.setTitle(command.getTitle());
        blog.setImages(command.getImages());
        blog.setContent(command.getContent());
        blog.setStatus(BlogState.DRAFT.getCode());
        blog.setVersion(0L);
        blog.setReviewRound(0);
        blog.setLiked(0);
        blog.setComments(0);
        if (blogMapper.insert(blog) != 1) {
            throw new IllegalStateException("草稿保存失败");
        }

        Blog savedBlog = blogMapper.selectById(blog.getId());
        BlogOwnerDTO result = BeanUtil.copyProperties(savedBlog, BlogOwnerDTO.class);
        return Result.ok(result);
    }

    @Transactional
    public Result edit(Long id, BlogDraftCommand command) {
        Long authorId = userId();
        Blog blog = ownedForUpdate(id, authorId);
        checkVersion(blog, command.getExpectedVersion());
        prepareContent(command);

        BlogTransitionContext context = new BlogTransitionContext(blog, authorId);
        context.setDraftCommand(command);
        stateHandler.handle(BlogStateEvent.EDIT, context);

        Blog updatedBlog = blogMapper.selectById(id);
        BlogOwnerDTO result = BeanUtil.copyProperties(updatedBlog, BlogOwnerDTO.class);
        return Result.ok(result);
    }

    @Transactional
    public Result submit(Long id, Long expectedVersion) {
        Long authorId = userId();
        Blog blog = ownedForUpdate(id, authorId);
        requireVersion(expectedVersion);

        // 同一次提交已经完成时直接返回，避免重复增加审核轮次。
        boolean alreadySubmitted = BlogState.fromCode(blog.getStatus()) == BlogState.PENDING_REVIEW
                && blog.getVersion() - 1 == expectedVersion;
        if (alreadySubmitted) {
            BlogOwnerDTO result = BeanUtil.copyProperties(blog, BlogOwnerDTO.class);
            return Result.ok(result);
        }
        checkVersion(blog, expectedVersion);

        BlogTransitionContext context = new BlogTransitionContext(blog, authorId);
        stateHandler.handle(BlogStateEvent.SUBMIT, context);

        Blog updatedBlog = blogMapper.selectById(id);
        BlogOwnerDTO result = BeanUtil.copyProperties(updatedBlog, BlogOwnerDTO.class);
        return Result.ok(result);
    }

    @Transactional
    public Result review(Long id, BlogReviewCommand command) {
        Long operatorId = reviewerId();
        validateReview(command);

        Blog blog = blogMapper.selectForUpdate(id);
        if (blog == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记不存在");
        }
        if (!command.getReviewRound().equals(blog.getReviewRound())) {
            throw conflict();
        }

        BlogState targetState = BlogState.REJECTED;
        BlogStateEvent event = BlogStateEvent.REJECT;
        if (command.getApproved()) {
            targetState = BlogState.PUBLISHED;
            event = BlogStateEvent.APPROVE;
        }

        // 幂等判断还要求版本和轮次一致，旧轮次的审批不能影响重新提交的笔记。
        boolean alreadyReviewed = BlogState.fromCode(blog.getStatus()) == targetState
                && blog.getVersion() - 1 == command.getExpectedVersion();
        if (alreadyReviewed) {
            BlogReviewDTO result = BeanUtil.copyProperties(blog, BlogReviewDTO.class);
            return Result.ok(result);
        }
        checkVersion(blog, command.getExpectedVersion());

        BlogTransitionContext context = new BlogTransitionContext(blog, operatorId);
        context.setReviewCommand(command);
        stateHandler.handle(event, context);

        Blog updatedBlog = blogMapper.selectById(id);
        BlogReviewDTO result = BeanUtil.copyProperties(updatedBlog, BlogReviewDTO.class);
        return Result.ok(result);
    }

    public Result myBlogs(Integer current) {
        Long authorId = userId();
        return queries.queryMyBlogs(authorId, current);
    }

    public Result myBlog(Long id) {
        Long authorId = userId();
        BlogOwnerDTO result = queries.queryOwnedBlog(id, authorId);
        return Result.ok(result);
    }

    public Result pendingReviews(Integer current) {
        reviewerId();
        return queries.queryPendingReviews(current);
    }

    public Result reviewDetail(Long id) {
        reviewerId();
        BlogReviewDTO result = queries.queryReviewDetail(id);
        return Result.ok(result);
    }

    private Long userId() {
        if (UserHolder.getUser() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return UserHolder.getUser().getId();
    }

    private Long reviewerId() {
        Long id = userId();
        User user = userMapper.selectById(id);
        if (user == null || !Integer.valueOf(1).equals(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有笔记审核权限");
        }
        return id;
    }

    private Blog ownedForUpdate(Long id, Long authorId) {
        Blog blog = blogMapper.selectForUpdate(id);
        if (blog == null || !authorId.equals(blog.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记不存在");
        }
        return blog;
    }

    private void requireVersion(Long version) {
        if (version == null || version < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供有效的 expectedVersion");
        }
    }

    private void checkVersion(Blog blog, Long version) {
        requireVersion(version);
        if (!version.equals(blog.getVersion())) {
            throw conflict();
        }
    }

    private void validateReview(BlogReviewCommand command) {
        requireVersion(command.getExpectedVersion());
        if (command.getReviewRound() == null || command.getReviewRound() < 1 || command.getApproved() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审核轮次和审核结果不能为空");
        }
        String reason = command.getReason();
        if (!command.getApproved() && (!StringUtils.hasText(reason) || reason.length() > 500)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "驳回原因须为 1 至 500 个字符");
        }
    }

    private void prepareContent(BlogDraftCommand command) {
        if (command.getShopId() == null || command.getShopId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择店铺");
        }
        command.setTitle(normalizeText(command.getTitle(), 255));
        command.setImages(normalizeText(command.getImages(), 2048));
        command.setContent(normalizeText(command.getContent(), 2048));
        if (StringUtils.hasText(command.getImages()) && command.getImages().split(",", -1).length > 9) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片最多 9 张");
        }
    }

    private String normalizeText(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        if (text.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "笔记内容超出长度限制");
        }
        return text;
    }

    private ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "笔记版本或审核状态已变更，请刷新后重试");
    }
}
