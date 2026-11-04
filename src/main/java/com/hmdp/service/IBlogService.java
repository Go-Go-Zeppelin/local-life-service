package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.blog.BlogOwnerDTO;
import com.hmdp.dto.blog.BlogReviewDTO;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface IBlogService {

    Result queryHotBlog(Integer current);

    Result queryBlogById(Long id);

    Result likeBlog(Long id);

    Result queryBlogLikes(Long id);

    Result queryMyBlogs(Long userId, Integer current);

    Result queryBlogsByUser(Long userId, Integer current);

    BlogOwnerDTO queryOwnedBlog(Long id, Long userId);

    Result queryPendingReviews(Integer current);

    BlogReviewDTO queryReviewDetail(Long id);

    Result queryBlogOfFollow(Long max, Integer offset);

}
