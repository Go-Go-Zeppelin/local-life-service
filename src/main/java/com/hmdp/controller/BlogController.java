package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.blog.BlogDraftCommand;
import com.hmdp.dto.blog.BlogReviewCommand;
import com.hmdp.dto.blog.BlogVersionCommand;
import com.hmdp.service.IBlogService;
import com.hmdp.service.blog.BlogPublishFacade;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/blog")
public class BlogController {
    private final IBlogService blogService;
    private final BlogPublishFacade publishFacade;

    public BlogController(IBlogService blogService, BlogPublishFacade publishFacade) {
        this.blogService = blogService;
        this.publishFacade = publishFacade;
    }

    /** 保存草稿，公开发布必须经过提交和审核。 */
    @PostMapping
    public Result saveBlog(@RequestBody BlogDraftCommand command) {
        return publishFacade.saveDraft(command);
    }

    @PutMapping("/{id}")
    public Result edit(@PathVariable Long id, @RequestBody BlogDraftCommand command) {
        return publishFacade.edit(id, command);
    }

    @PostMapping("/{id}/submit")
    public Result submit(@PathVariable Long id, @RequestBody BlogVersionCommand command) {
        return publishFacade.submit(id, command.getExpectedVersion());
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return publishFacade.myBlogs(current);
    }

    @GetMapping("/of/me/{id}")
    public Result myBlog(@PathVariable Long id) {
        return publishFacade.myBlog(id);
    }

    @GetMapping("/reviews")
    public Result reviews(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return publishFacade.pendingReviews(current);
    }

    @GetMapping("/reviews/{id}")
    public Result reviewDetail(@PathVariable Long id) {
        return publishFacade.reviewDetail(id);
    }

    @PostMapping("/{id}/review")
    public Result review(@PathVariable Long id, @RequestBody BlogReviewCommand command) {
        return publishFacade.review(id, command);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable Long id) {
        return blogService.likeBlog(id);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable Long id) {
        return blogService.queryBlogById(id);
    }

    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable Long id) {
        return blogService.queryBlogLikes(id);
    }

    @GetMapping("/of/user")
    public Result queryBlogByUserId(@RequestParam(value = "current", defaultValue = "1") Integer current,
                                    @RequestParam("id") Long id) {
        return blogService.queryBlogsByUser(id, current);
    }

    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(@RequestParam("lastId") Long max,
                                    @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        return blogService.queryBlogOfFollow(max, offset);
    }
}
