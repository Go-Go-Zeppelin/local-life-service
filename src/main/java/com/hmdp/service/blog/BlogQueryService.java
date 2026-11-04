package com.hmdp.service.blog;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.blog.BlogOwnerDTO;
import com.hmdp.dto.blog.BlogPublicDTO;
import com.hmdp.dto.blog.BlogReviewDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

@Service
public class BlogQueryService implements IBlogService {
    private static final int FEED_PAGE_SIZE = 2;
    private static final int FEED_SCAN_BATCH = 20;
    private static final int FEED_SCAN_LIMIT = 200;

    @Resource private BlogMapper blogMapper;
    @Resource private UserMapper userMapper;
    @Resource private StringRedisTemplate redis;

    @Override
    public Result queryHotBlog(Integer current) {
        return publicPage(current, published().orderByDesc("liked").orderByDesc("id"));
    }

    @Override
    public Result queryBlogsByUser(Long userId, Integer current) {
        return publicPage(current, published().eq("user_id", userId).orderByDesc("publish_time").orderByDesc("id"));
    }

    @Override
    public Result queryBlogById(Long id) {
        return Result.ok(publicView(requirePublished(id)));
    }

    @Override
    public Result queryMyBlogs(Long userId, Integer current) {
        Page<Blog> page = blogMapper.selectPage(page(current),
                new QueryWrapper<Blog>().eq("user_id", userId).orderByDesc("id"));
        return Result.ok(page.getRecords().stream().map(blog -> BeanUtil.copyProperties(blog, BlogOwnerDTO.class))
                .collect(Collectors.toList()), page.getTotal());
    }

    @Override
    public BlogOwnerDTO queryOwnedBlog(Long id, Long userId) {
        Blog blog = blogMapper.selectById(id);
        if (blog == null || !userId.equals(blog.getUserId())) {
            throw notFound();
        }
        return BeanUtil.copyProperties(blog, BlogOwnerDTO.class);
    }

    @Override
    public Result queryPendingReviews(Integer current) {
        Page<Blog> page = blogMapper.selectPage(page(current), new QueryWrapper<Blog>()
                .eq("status", BlogStatus.PENDING_REVIEW.getCode()).orderByAsc("id"));
        return Result.ok(page.getRecords().stream().map(blog -> BeanUtil.copyProperties(blog, BlogReviewDTO.class))
                .collect(Collectors.toList()), page.getTotal());
    }

    @Override
    public BlogReviewDTO queryReviewDetail(Long id) {
        Blog blog = blogMapper.selectById(id);
        if (blog == null) {
            throw notFound();
        }
        return BeanUtil.copyProperties(blog, BlogReviewDTO.class);
    }

    @Override
    public Result likeBlog(Long id) {
        requirePublished(id);
        Long userId = currentUserId();
        String key = BLOG_LIKED_KEY + id;
        Double score = redis.opsForZSet().score(key, userId.toString());
        UpdateWrapper<Blog> update = new UpdateWrapper<Blog>().eq("id", id)
                .eq("status", BlogStatus.PUBLISHED.getCode());
        if (score == null) {
            if (blogMapper.update(null, update.setSql("liked = liked + 1")) == 1) {
                redis.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else if (blogMapper.update(null, update.gt("liked", 0).setSql("liked = liked - 1")) == 1) {
            redis.opsForZSet().remove(key, userId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        requirePublished(id);
        Set<String> likedUsers = redis.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if (likedUsers == null || likedUsers.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = likedUsers.stream().map(Long::valueOf).collect(Collectors.toList());
        Map<Long, User> users = userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        List<UserDTO> result = new ArrayList<>();
        for (Long userId : ids) {
            if (users.containsKey(userId)) {
                result.add(BeanUtil.copyProperties(users.get(userId), UserDTO.class));
            }
        }
        return Result.ok(result);
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        if (max == null || max < 0 || offset == null || offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "关注流游标无效");
        }
        String key = FEED_KEY + currentUserId();
        long cursorTime = max;
        int cursorOffset = offset;
        int scanned = 0;
        List<BlogPublicDTO> result = new ArrayList<>();
        while (result.size() < FEED_PAGE_SIZE && scanned < FEED_SCAN_LIMIT) {
            Set<ZSetOperations.TypedTuple<String>> batch = redis.opsForZSet()
                    .reverseRangeByScoreWithScores(key, 0, cursorTime, cursorOffset, FEED_SCAN_BATCH);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            List<Long> ids = batch.stream().map(tuple -> Long.valueOf(tuple.getValue())).collect(Collectors.toList());
            Map<Long, Blog> visible = new HashMap<>();
            for (Blog blog : blogMapper.selectList(published().in("id", ids))) {
                visible.put(blog.getId(), blog);
            }
            for (ZSetOperations.TypedTuple<String> tuple : batch) {
                long time = tuple.getScore().longValue();
                cursorOffset = time == cursorTime ? Math.addExact(cursorOffset, 1) : 1;
                cursorTime = time;
                scanned++;
                Blog blog = visible.get(Long.valueOf(tuple.getValue()));
                if (blog != null) {
                    result.add(publicView(blog));
                }
                // 批量预读不代表已消费。只推进到实际处理的位置，保留剩余记录给下一页。
                if (result.size() == FEED_PAGE_SIZE || scanned == FEED_SCAN_LIMIT) {
                    break;
                }
            }
        }
        Set<String> next = redis.opsForZSet().reverseRangeByScore(key, 0, cursorTime, cursorOffset, 1);
        ScrollResult page = new ScrollResult();
        page.setList(result);
        page.setMinTime(cursorTime);
        page.setOffset(cursorOffset);
        page.setHasMore(next != null && !next.isEmpty());
        return Result.ok(page);
    }

    private QueryWrapper<Blog> published() {
        return new QueryWrapper<Blog>().eq("status", BlogStatus.PUBLISHED.getCode());
    }

    private Blog requirePublished(Long id) {
        Blog blog = blogMapper.selectById(id);
        if (blog == null || !Integer.valueOf(BlogStatus.PUBLISHED.getCode()).equals(blog.getStatus())) {
            throw notFound();
        }
        return blog;
    }

    private Result publicPage(Integer current, QueryWrapper<Blog> query) {
        Page<Blog> page = blogMapper.selectPage(page(current), query);
        return Result.ok(page.getRecords().stream().map(this::publicView).collect(Collectors.toList()), page.getTotal());
    }

    private Page<Blog> page(Integer current) {
        if (current == null || current < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码须大于零");
        }
        return new Page<>(current, SystemConstants.MAX_PAGE_SIZE);
    }

    private BlogPublicDTO publicView(Blog blog) {
        BlogPublicDTO view = BeanUtil.copyProperties(blog, BlogPublicDTO.class);
        User author = userMapper.selectById(blog.getUserId());
        if (author != null) {
            view.setName(author.getNickName());
            view.setIcon(author.getIcon());
        }
        UserDTO user = UserHolder.getUser();
        view.setIsLike(user != null && redis.opsForZSet().score(BLOG_LIKED_KEY + blog.getId(),
                user.getId().toString()) != null);
        return view;
    }

    private Long currentUserId() {
        if (UserHolder.getUser() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return UserHolder.getUser().getId();
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记不存在");
    }
}
