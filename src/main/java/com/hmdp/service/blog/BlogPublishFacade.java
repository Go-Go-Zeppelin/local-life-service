package com.hmdp.service.blog;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.blog.BlogDraftCommand;
import com.hmdp.dto.blog.BlogOwnerDTO;
import com.hmdp.dto.blog.BlogReviewCommand;
import com.hmdp.dto.blog.BlogReviewDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.UserHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import javax.annotation.Resource;

/** 用户发布及审核入口，事务方法由独立 Bean 代理执行。 */
@Service
public class BlogPublishFacade {
    @Resource private BlogLifecycleService lifecycle;
    @Resource private IBlogService queries;
    @Resource private UserMapper userMapper;

    public Result saveDraft(BlogDraftCommand command) {
        return Result.ok(BeanUtil.copyProperties(lifecycle.saveDraft(userId(), command), BlogOwnerDTO.class));
    }

    public Result edit(Long id, BlogDraftCommand command) {
        return Result.ok(BeanUtil.copyProperties(lifecycle.edit(userId(), id, command), BlogOwnerDTO.class));
    }

    public Result submit(Long id, Long version) {
        return Result.ok(BeanUtil.copyProperties(lifecycle.submit(userId(), id, version), BlogOwnerDTO.class));
    }

    public Result myBlogs(Integer current) {
        return queries.queryMyBlogs(userId(), current);
    }

    public Result myBlog(Long id) {
        return Result.ok(queries.queryOwnedBlog(id, userId()));
    }

    public Result pendingReviews(Integer current) {
        reviewerId();
        return queries.queryPendingReviews(current);
    }

    public Result reviewDetail(Long id) {
        reviewerId();
        return Result.ok(queries.queryReviewDetail(id));
    }

    public Result review(Long id, BlogReviewCommand command) {
        return Result.ok(BeanUtil.copyProperties(lifecycle.review(reviewerId(), id, command), BlogReviewDTO.class));
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
}
