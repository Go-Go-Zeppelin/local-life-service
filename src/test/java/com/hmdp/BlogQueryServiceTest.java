package com.hmdp;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.blog.BlogPublicDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.blog.BlogQueryService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlogQueryServiceTest {
    private BlogMapper blogMapper;
    private UserMapper userMapper;
    private StringRedisTemplate redis;
    private RedisConnection connection;
    private RedisZSetCommands zSetCommands;
    private BlogQueryService service;

    @BeforeEach
    void setUp() {
        blogMapper = mock(BlogMapper.class);
        userMapper = mock(UserMapper.class);
        redis = mock(StringRedisTemplate.class);
        connection = mock(RedisConnection.class);
        zSetCommands = mock(RedisZSetCommands.class);
        service = new BlogQueryService();
        ReflectionTestUtils.setField(service, "blogMapper", blogMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "redis", redis);
        when(redis.getStringSerializer()).thenReturn(new StringRedisSerializer());
        when(connection.zSetCommands()).thenReturn(zSetCommands);
        UserHolder.removeUser();
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void publicPageBatchesAuthorsAndLikeChecksInPageOrder() {
        Blog first = blog(101L, 7L);
        Blog second = blog(102L, 7L);
        Blog third = blog(103L, 8L);
        Page<Blog> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(first, second, third));
        page.setTotal(3);
        doReturn(page).when(blogMapper).selectPage(any(Page.class), any(QueryWrapper.class));

        User author = new User();
        author.setId(7L);
        author.setNickName("作者");
        author.setIcon("icon");
        when(userMapper.selectBatchIds(Arrays.asList(7L, 8L))).thenReturn(Collections.singletonList(author));
        UserDTO currentUser = new UserDTO();
        currentUser.setId(99L);
        UserHolder.saveUser(currentUser);
        when(redis.executePipelined(any(RedisCallback.class)))
                .thenAnswer(invocation -> {
                    RedisCallback<?> callback = invocation.getArgument(0);
                    callback.doInRedis(connection);
                    return Arrays.<Object>asList(null, 0D, 1D);
                });

        Result result = service.queryHotBlog(1);

        List<BlogPublicDTO> views = (List<BlogPublicDTO>) result.getData();
        assertEquals(3, views.size());
        assertEquals("作者", views.get(0).getName());
        assertFalse(views.get(0).getIsLike());
        assertEquals("作者", views.get(1).getName());
        assertEquals(Boolean.TRUE, views.get(1).getIsLike());
        assertNull(views.get(2).getName());
        assertEquals(Boolean.TRUE, views.get(2).getIsLike());
        verify(userMapper, times(1)).selectBatchIds(Arrays.asList(7L, 8L));
        verify(userMapper, never()).selectById(any());
        verify(redis, times(1)).executePipelined(any(RedisCallback.class));
        verify(zSetCommands, times(3)).zScore(any(byte[].class), any(byte[].class));
    }

    @Test
    void emptyPublicPageSkipsAuthorAndLikeQueries() {
        Page<Blog> page = new Page<>(1, 10);
        page.setRecords(Collections.emptyList());
        page.setTotal(0);
        doReturn(page).when(blogMapper).selectPage(any(Page.class), any(QueryWrapper.class));

        Result result = service.queryHotBlog(1);

        assertEquals(Collections.emptyList(), result.getData());
        verify(userMapper, never()).selectBatchIds(anyList());
        verify(redis, never()).executePipelined(any(RedisCallback.class));
    }

    private Blog blog(Long id, Long userId) {
        Blog blog = new Blog();
        blog.setId(id);
        blog.setUserId(userId);
        blog.setStatus(2);
        return blog;
    }
}
