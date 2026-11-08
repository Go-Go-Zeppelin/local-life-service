package com.hmdp;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.hmdp.config.MybatisConfig;
import com.hmdp.config.BlogStateMachineConfig;
import com.hmdp.action.BlogStateAction;
import com.hmdp.handler.BlogStateHandler;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.controller.BlogController;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.blog.*;
import com.hmdp.entity.BlogFeedOutbox;
import com.hmdp.mapper.BlogFeedOutboxMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.blog.*;
import com.hmdp.task.BlogFeedOutboxWorker;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 专用 blog_test 数据库及 Redis DB 14，需显式启用。 */
@SpringJUnitConfig(BlogPublishIntegrationTest.TestConfig.class)
@EnabledIfEnvironmentVariable(named = "BLOG_INTEGRATION_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlogPublishIntegrationTest {
    @TestConfiguration
    @EnableTransactionManagement
    @MapperScan("com.hmdp.mapper")
    @Import({BlogStateMachineConfig.class, BlogStateAction.class, BlogStateHandler.class,
            BlogPublishFacade.class, BlogQueryService.class,
            BlogFeedOutboxWorker.class, MybatisConfig.class})
    static class TestConfig {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13307/blog_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                    "root", "blog-test");
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source, MybatisPlusInterceptor interceptor) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setPlugins(interceptor);
            return factory.getObject();
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean LettuceConnectionFactory redisConnectionFactory() {
            LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 16380);
            factory.setDatabase(14);
            return factory;
        }
        @Bean StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private BlogPublishFacade facade;
    @Autowired private BlogStateAction stateAction;
    @Autowired private IBlogService queries;
    @Autowired private BlogFeedOutboxWorker worker;
    @Autowired private BlogFeedOutboxMapper outboxMapper;
    private MockMvc mvc;

    @BeforeAll
    void schema() {
        new ResourceDatabasePopulator(new ByteArrayResource(
                "SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION';".getBytes(StandardCharsets.UTF_8)),
                new ClassPathResource("db/hmdp.sql")).execute(dataSource);
    }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM tb_blog_feed_outbox");
        jdbc.update("DELETE FROM tb_blog");
        jdbc.update("DELETE FROM tb_follow");
        jdbc.update("DELETE FROM tb_user");
        for (long id : new long[]{1, 2, 10, 11}) {
            jdbc.update("INSERT INTO tb_user (id,phone,nick_name,role) VALUES (?,?,?,?)",
                    id, String.format("%011d", id), "user" + id, id < 3 ? 1 : 0);
        }
        redis.execute(connection -> { connection.serverCommands().flushDb(); return null; }, true);
        login(10L);
        BlogController controller = new BlogController(queries, facade);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new WebExceptionAdvice()).build();
    }

    @AfterEach void clearUser() { UserHolder.removeUser(); }

    private void login(long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        UserHolder.saveUser(user);
    }

    private BlogDraftCommand content(Long version) {
        BlogDraftCommand command = new BlogDraftCommand();
        command.setShopId(1L);
        command.setTitle("探店笔记");
        command.setContent("真实的体验内容");
        command.setImages("/imgs/test.jpg");
        command.setExpectedVersion(version);
        return command;
    }

    private BlogOwnerDTO draft() { return (BlogOwnerDTO) facade.saveDraft(content(null)).getData(); }

    private BlogOwnerDTO pending() {
        BlogOwnerDTO draft = draft();
        return (BlogOwnerDTO) facade.submit(draft.getId(), draft.getVersion()).getData();
    }

    private BlogReviewCommand decision(BlogOwnerDTO blog, boolean approved) {
        BlogReviewCommand command = new BlogReviewCommand();
        command.setExpectedVersion(blog.getVersion());
        command.setReviewRound(blog.getReviewRound());
        command.setApproved(approved);
        command.setReason(approved ? null : "请完善内容");
        return command;
    }

    private BlogOwnerDTO publish() {
        login(10L);
        BlogOwnerDTO blog = pending();
        login(1L);
        return (BlogOwnerDTO) facade.review(blog.getId(), decision(blog, true)).getData();
    }

    private int countEvents() { return jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_feed_outbox", Integer.class); }

    private void statusIs(Long id, int expected) {
        assertEquals(expected, jdbc.queryForObject("SELECT status FROM tb_blog WHERE id=?", Integer.class, id));
    }

    private void expectStatus(HttpStatus status, Runnable action) {
        assertEquals(status, assertThrows(ResponseStatusException.class, action::run).getStatus());
    }

    @Test
    void reviewRoundPreventsOldDecisionAndOnlyFirstApprovalCreatesEvent() {
        BlogOwnerDTO first = pending();
        login(1L);
        BlogReviewCommand oldApproval = decision(first, true);
        BlogReviewCommand rejection = decision(first, false);
        facade.review(first.getId(), rejection);
        facade.review(first.getId(), rejection);
        assertEquals(0, countEvents());
        login(10L);
        BlogOwnerDTO rejected = (BlogOwnerDTO) facade.myBlog(first.getId()).getData();
        BlogOwnerDTO edited = (BlogOwnerDTO) facade.edit(first.getId(), content(rejected.getVersion())).getData();
        BlogOwnerDTO second = (BlogOwnerDTO) facade.submit(first.getId(), edited.getVersion()).getData();
        assertEquals(2, second.getReviewRound());
        login(1L);
        expectStatus(HttpStatus.CONFLICT, () -> facade.review(first.getId(), oldApproval));
        BlogReviewCommand approval = decision(second, true);
        facade.review(first.getId(), approval);
        facade.review(first.getId(), approval);
        expectStatus(HttpStatus.CONFLICT, () -> facade.review(first.getId(), oldApproval));
        expectStatus(HttpStatus.CONFLICT, () -> facade.review(first.getId(), decision(second, false)));
        assertEquals(1, countEvents());
        statusIs(first.getId(), 2);
        login(10L);
        BlogOwnerDTO published = (BlogOwnerDTO) facade.myBlog(first.getId()).getData();
        expectStatus(HttpStatus.CONFLICT, () -> facade.edit(first.getId(), content(published.getVersion())));
    }

    @Test
    void databasePermissionIsCheckedForEveryReviewEntryAndOwnerCannotEditOthers() {
        BlogOwnerDTO blog = pending();
        expectStatus(HttpStatus.FORBIDDEN, () -> facade.pendingReviews(1));
        expectStatus(HttpStatus.FORBIDDEN, () -> facade.reviewDetail(blog.getId()));
        expectStatus(HttpStatus.FORBIDDEN, () -> facade.review(blog.getId(), decision(blog, true)));
        login(11L);
        expectStatus(HttpStatus.NOT_FOUND, () -> facade.myBlog(blog.getId()));
        expectStatus(HttpStatus.NOT_FOUND, () -> facade.edit(blog.getId(), content(blog.getVersion())));
        expectStatus(HttpStatus.NOT_FOUND, () -> facade.submit(blog.getId(), blog.getVersion()));
        login(1L);
        assertEquals(1L, facade.pendingReviews(1).getTotal());
        jdbc.update("UPDATE tb_user SET role=0 WHERE id=1");
        expectStatus(HttpStatus.FORBIDDEN, () -> facade.review(blog.getId(), decision(blog, true)));
    }

    @Test
    void requestCannotForgePublicationAndPublicResponsesDoNotLeakReviewFields() throws Exception {
        mvc.perform(post("/blog").contentType(MediaType.APPLICATION_JSON).content(
                "{\"shopId\":1,\"title\":\"草稿\",\"content\":\"正文\",\"status\":2,\"userId\":1,\"reviewerId\":1,\"version\":50}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value(0))
                .andExpect(jsonPath("$.data.userId").value(10)).andExpect(jsonPath("$.data.version").value(0));
        BlogOwnerDTO published = publish();
        login(11L);
        mvc.perform(get("/blog/" + published.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").doesNotExist())
                .andExpect(jsonPath("$.data.reviewerId").doesNotExist())
                .andExpect(jsonPath("$.data.version").doesNotExist());
        mvc.perform(get("/blog/reviews")).andExpect(status().isForbidden());
    }

    @Test
    void httpPublicationFlowUsesRedisLoginAndDatabaseReviewPermission() throws Exception {
        BlogController controller = new BlogController(queries, facade);
        MockMvc http = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new WebExceptionAdvice())
                .addInterceptors(new RefreshTokenInterceptor(redis), new LoginInterceptor()).build();
        for (String[] session : new String[][]{{"author", "10"}, {"reviewer", "1"}, {"reader", "11"}}) {
            redis.opsForHash().put("login:token:" + session[0], "id", session[1]);
        }
        UserHolder.removeUser();
        http.perform(get("/blog/reviews")).andExpect(status().isUnauthorized());
        String created = http.perform(post("/blog").header("authorization", "author")
                .contentType(MediaType.APPLICATION_JSON).content("{\"shopId\":1,\"title\":\"HTTP笔记\",\"content\":\"真实入口\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = new ObjectMapper().readTree(created).get("data");
        long id = data.get("id").asLong();
        http.perform(post("/blog/" + id + "/submit").header("authorization", "author")
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewRound").value(1));
        String approval = "{\"expectedVersion\":1,\"reviewRound\":1,\"approved\":true}";
        http.perform(post("/blog/" + id + "/review").header("authorization", "author")
                .contentType(MediaType.APPLICATION_JSON).content(approval)).andExpect(status().isForbidden());
        http.perform(get("/blog/reviews").header("authorization", "reviewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        http.perform(get("/blog/reviews/" + id).header("authorization", "reviewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        jdbc.update("INSERT INTO tb_follow (user_id,follow_user_id) VALUES (11,10)");
        http.perform(post("/blog/" + id + "/review").header("authorization", "reviewer")
                .contentType(MediaType.APPLICATION_JSON).content(approval))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value(2));
        assertEquals(1, worker.processDue(20));
        http.perform(get("/blog/of/follow").header("authorization", "reader")
                .param("lastId", String.valueOf(Long.MAX_VALUE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.list[0].id").value(id))
                .andExpect(jsonPath("$.data.list[0].reviewerId").doesNotExist());
        assertNull(UserHolder.getUser());
    }

    @Test
    void unpublishedBlogsAreHiddenFromAllPublicReadsAndInteractions() {
        BlogOwnerDTO hidden = draft();
        BlogOwnerDTO published = publish();
        login(11L);
        assertEquals(1L, queries.queryHotBlog(1).getTotal());
        assertEquals(1L, queries.queryBlogsByUser(10L, 1).getTotal());
        expectStatus(HttpStatus.NOT_FOUND, () -> queries.queryBlogById(hidden.getId()));
        expectStatus(HttpStatus.NOT_FOUND, () -> queries.likeBlog(hidden.getId()));
        expectStatus(HttpStatus.NOT_FOUND, () -> queries.queryBlogLikes(hidden.getId()));
        assertEquals(published.getId(), ((BlogPublicDTO) queries.queryBlogById(published.getId()).getData()).getId());
        login(10L);
        assertEquals(2L, facade.myBlogs(1).getTotal());
    }

    @Test
    void publishedUserPagesKeepOrderTotalAndPublicFields() throws Exception {
        for (long id = 101; id <= 114; id++) {
            insertBlog(id, 2);
        }
        insertBlog(115, 0);
        insertBlog(116, 2);
        jdbc.update("UPDATE tb_blog SET publish_time='2026-01-01' WHERE id=101");
        jdbc.update("UPDATE tb_blog SET publish_time='2026-01-02' WHERE id BETWEEN 102 AND 112");
        jdbc.update("UPDATE tb_blog SET publish_time='2026-01-03' WHERE id IN (115,116)");
        jdbc.update("UPDATE tb_blog SET user_id=11 WHERE id=116");
        login(11L);
        redis.opsForZSet().add(BLOG_LIKED_KEY + 112L, "11", 0D);

        mvc.perform(get("/blog/of/user").param("id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(14))
                .andExpect(jsonPath("$.data[*].id").value(contains(112, 111, 110, 109, 108, 107, 106, 105, 104, 103)))
                .andExpect(jsonPath("$.data[0].name").value("user10"))
                .andExpect(jsonPath("$.data[0].isLike").value(true))
                .andExpect(jsonPath("$.data[1].isLike").value(false))
                .andExpect(jsonPath("$.data[0].publishTime").exists())
                .andExpect(jsonPath("$.data[0].status").doesNotExist());
        mvc.perform(get("/blog/of/user").param("id", "10").param("current", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(14))
                .andExpect(jsonPath("$.data[*].id").value(contains(102, 101, 114, 113)))
                .andExpect(jsonPath("$.data[2].publishTime").doesNotExist());
        mvc.perform(get("/blog/of/user").param("id", "10").param("current", String.valueOf(Integer.MAX_VALUE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(14))
                .andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/blog/of/user").param("id", "10").param("current", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyDraftCannotSubmitAndStaleOrMissingVersionsCannotEdit() {
        BlogDraftCommand empty = content(null);
        empty.setTitle("");
        BlogOwnerDTO blog = (BlogOwnerDTO) facade.saveDraft(empty).getData();
        expectStatus(HttpStatus.BAD_REQUEST, () -> facade.submit(blog.getId(), blog.getVersion()));
        expectStatus(HttpStatus.BAD_REQUEST, () -> facade.edit(blog.getId(), content(null)));
        BlogOwnerDTO edited = (BlogOwnerDTO) facade.edit(blog.getId(), content(blog.getVersion())).getData();
        expectStatus(HttpStatus.CONFLICT, () -> facade.edit(blog.getId(), content(blog.getVersion())));
        facade.submit(blog.getId(), edited.getVersion());
        facade.submit(blog.getId(), edited.getVersion());
        statusIs(blog.getId(), 1);
        expectStatus(HttpStatus.CONFLICT, () -> facade.edit(blog.getId(), content(edited.getVersion() + 1)));
    }

    @Test
    void outboxInsertFailureRollsBackPublication() {
        BlogOwnerDTO blog = pending();
        BlogFeedOutboxMapper broken = mock(BlogFeedOutboxMapper.class);
        Thread caller = Thread.currentThread();
        when(broken.insert(any())).thenAnswer(invocation -> {
            assertSame(caller, Thread.currentThread());
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(2, jdbc.queryForObject("SELECT status FROM tb_blog WHERE id=?", Integer.class, blog.getId()));
            throw new IllegalStateException("outbox unavailable");
        });
        Object target = AopTestUtils.getUltimateTargetObject(stateAction);
        ReflectionTestUtils.setField(target, "outboxMapper", broken);
        login(1L);
        try {
            assertThrows(IllegalStateException.class, () -> facade.review(blog.getId(), decision(blog, true)));
        } finally {
            ReflectionTestUtils.setField(target, "outboxMapper", outboxMapper);
        }
        statusIs(blog.getId(), 1);
        assertNull(jdbc.queryForObject("SELECT publish_time FROM tb_blog WHERE id=?", Object.class, blog.getId()));
        assertEquals(blog.getVersion(), jdbc.queryForObject("SELECT version FROM tb_blog WHERE id=?", Long.class, blog.getId()));
        assertEquals(0, countEvents());
        facade.review(blog.getId(), decision(blog, true));
        assertEquals(1, countEvents());
    }

    @Test
    void concurrentApprovalAndRejectionProduceOneDecision() throws Exception {
        BlogOwnerDTO blog = pending();
        List<Integer> outcomes = race(() -> reviewAs(1L, blog, true), () -> reviewAs(2L, blog, false));
        assertEquals(1, Collections.frequency(outcomes, 200));
        assertEquals(1, Collections.frequency(outcomes, 409));
        int state = jdbc.queryForObject("SELECT status FROM tb_blog WHERE id=?", Integer.class, blog.getId());
        assertEquals(state == 2 ? 1 : 0, countEvents());
    }

    private int reviewAs(long reviewer, BlogOwnerDTO blog, boolean approved) {
        login(reviewer);
        try {
            facade.review(blog.getId(), decision(blog, approved));
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatus().value();
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void concurrentRepeatedApprovalCreatesOneEventAndPreservesFirstReviewer() throws Exception {
        BlogOwnerDTO blog = pending();
        List<Integer> outcomes = race(() -> reviewAs(1L, blog, true), () -> reviewAs(2L, blog, true));
        assertEquals(Arrays.asList(200, 200), outcomes);
        assertEquals(1, countEvents());
        Long reviewer = jdbc.queryForObject("SELECT reviewer_id FROM tb_blog WHERE id=?", Long.class, blog.getId());
        assertTrue(reviewer == 1L || reviewer == 2L);
        login(reviewer == 1L ? 2L : 1L);
        facade.review(blog.getId(), decision(blog, true));
        assertEquals(reviewer, jdbc.queryForObject("SELECT reviewer_id FROM tb_blog WHERE id=?", Long.class, blog.getId()));
    }

    @Test
    void concurrentEventsForDifferentBlogsKeepTheirContextsSeparate() throws Exception {
        BlogOwnerDTO approved = pending();
        BlogOwnerDTO rejected = pending();
        List<Integer> outcomes = race(() -> reviewAs(1L, approved, true), () -> reviewAs(2L, rejected, false));
        assertEquals(Arrays.asList(200, 200), outcomes);
        statusIs(approved.getId(), 2);
        statusIs(rejected.getId(), 3);
        assertEquals(1L, jdbc.queryForObject("SELECT reviewer_id FROM tb_blog WHERE id=?", Long.class, approved.getId()));
        assertEquals(2L, jdbc.queryForObject("SELECT reviewer_id FROM tb_blog WHERE id=?", Long.class, rejected.getId()));
        assertEquals(approved.getId(), jdbc.queryForObject("SELECT blog_id FROM tb_blog_feed_outbox", Long.class));
    }

    @Test
    void editAndSubmitCannotBothApplyToSameContentVersion() throws Exception {
        BlogOwnerDTO blog = draft();
        List<Integer> outcomes = race(() -> editOrSubmit(blog, true), () -> editOrSubmit(blog, false));
        assertEquals(1, Collections.frequency(outcomes, 200));
        assertEquals(1, Collections.frequency(outcomes, 409));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM tb_blog WHERE id=?", Long.class, blog.getId()));
    }

    private int editOrSubmit(BlogOwnerDTO blog, boolean edit) {
        login(10L);
        try {
            if (edit) {
                facade.edit(blog.getId(), content(blog.getVersion()));
            } else {
                facade.submit(blog.getId(), blog.getVersion());
            }
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatus().value();
        } finally {
            UserHolder.removeUser();
        }
    }

    private List<Integer> race(Callable<Integer> first, Callable<Integer> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> left = pool.submit(() -> { start.await(); return first.call(); });
            Future<Integer> right = pool.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return Arrays.asList(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void partialFeedFailureIsRetriedWithSameScoreAndDoesNotRepublish() {
        jdbc.update("INSERT INTO tb_follow (user_id,follow_user_id) VALUES (11,10),(1,10)");
        BlogOwnerDTO blog = publish();
        assertNull(redis.opsForZSet().score("feed:11", blog.getId().toString()));
        BlogFeedOutbox event = outboxMapper.selectDue(1).get(0);
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> writes = mock(ZSetOperations.class);
        when(broken.opsForZSet()).thenReturn(writes);
        AtomicInteger calls = new AtomicInteger();
        when(writes.add(anyString(), anyString(), anyDouble())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) { throw new IllegalStateException("Redis unavailable"); }
            return redis.opsForZSet().add(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
        });
        ReflectionTestUtils.setField(worker, "redis", broken);
        try { assertEquals(0, worker.processDue(20)); }
        finally { ReflectionTestUtils.setField(worker, "redis", redis); }
        assertEquals(0, outboxMapper.selectById(event.getId()).getStatus());
        assertEquals(event.getPublishTimeMillis().doubleValue(), redis.opsForZSet().score("feed:11", blog.getId().toString()));
        jdbc.update("UPDATE tb_blog_feed_outbox SET next_retry_time=NOW(3)");
        assertEquals(1, worker.processDue(20));
        assertEquals(0, worker.processDue(20));
        assertEquals(1L, redis.opsForZSet().zCard("feed:11"));
        assertEquals(event.getPublishTimeMillis().doubleValue(), redis.opsForZSet().score("feed:11", blog.getId().toString()));
        assertEquals(event.getPublishTimeMillis().doubleValue(), redis.opsForZSet().score("feed:1", blog.getId().toString()));
        assertEquals(1, countEvents());
    }

    @Test
    void leaseRecoveryAndClaimTokensFenceStaleWorkers() throws Exception {
        publish();
        BlogFeedOutbox event = outboxMapper.selectDue(1).get(0);
        assertEquals(1, outboxMapper.claim(event.getId(), "old", 60));
        assertEquals(0, worker.processDue(20));
        jdbc.update("UPDATE tb_blog_feed_outbox SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3))");
        List<Integer> claims = race(() -> outboxMapper.claim(event.getId(), "new-a", 60),
                () -> outboxMapper.claim(event.getId(), "new-b", 60));
        assertEquals(1, Collections.frequency(claims, 1));
        assertEquals(1, Collections.frequency(claims, 0));
        assertEquals(0, outboxMapper.complete(event.getId(), "old"));
        assertEquals(0, outboxMapper.retry(event.getId(), "old", 1, "stale"));
        assertEquals(0, outboxMapper.renew(event.getId(), "old", 60));
        jdbc.update("UPDATE tb_blog_feed_outbox SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3))");
        assertEquals(1, worker.processDue(20));
        assertEquals(2, outboxMapper.selectById(event.getId()).getStatus());
    }

    @Test
    void feedOverfetchAndEqualScoresDoNotSkipUnreturnedBlogs() {
        for (long id = 100; id < 125; id++) {
            insertBlog(id, 2);
            redis.opsForZSet().add("feed:11", String.valueOf(id), 1000);
        }
        login(11L);
        long cursor = Long.MAX_VALUE;
        int offset = 0;
        Set<Long> found = new HashSet<>();
        for (int page = 0; page < 20; page++) {
            ScrollResult result = (ScrollResult) queries.queryBlogOfFollow(cursor, offset).getData();
            for (Object value : result.getList()) {
                assertTrue(found.add(((BlogPublicDTO) value).getId()), "分页重复");
            }
            cursor = result.getMinTime();
            offset = result.getOffset();
            if (!result.isHasMore()) { break; }
        }
        assertEquals(25, found.size());
    }

    @Test
    void filteredEmptyPageStillReturnsContinuationCursor() {
        for (long id = 100; id < 300; id++) {
            insertBlog(id, 0);
            redis.opsForZSet().add("feed:11", String.valueOf(id), 1000 + id);
        }
        insertBlog(300L, 2);
        redis.opsForZSet().add("feed:11", "300", 1);
        login(11L);
        ScrollResult first = (ScrollResult) queries.queryBlogOfFollow(Long.MAX_VALUE, 0).getData();
        assertTrue(first.getList().isEmpty());
        assertTrue(first.isHasMore());
        ScrollResult second = (ScrollResult) queries.queryBlogOfFollow(first.getMinTime(), first.getOffset()).getData();
        assertEquals(300L, ((BlogPublicDTO) second.getList().get(0)).getId());
        assertFalse(second.isHasMore());
    }

    private void insertBlog(long id, int state) {
        jdbc.update("INSERT INTO tb_blog (id,shop_id,user_id,title,images,content,status) VALUES (?,1,10,'标题','','正文',?)", id, state);
    }

    @Test
    void migrationPublishesHistoricalRowsAndNewRowsDefaultToDraft() throws Exception {
        jdbc.execute("CREATE TABLE tb_blog_migration_test LIKE tb_blog");
        jdbc.execute("CREATE TABLE tb_user_migration_test LIKE tb_user");
        jdbc.execute("CREATE TABLE tb_follow_migration_test LIKE tb_follow");
        try {
            jdbc.execute("ALTER TABLE tb_blog_migration_test DROP INDEX idx_blog_status_id, DROP INDEX idx_blog_author_status, " +
                    "DROP INDEX idx_blog_author_status_publish_time, DROP COLUMN status, " +
                    "DROP COLUMN version, DROP COLUMN review_round, DROP COLUMN reviewer_id, " +
                    "DROP COLUMN review_time, DROP COLUMN reject_reason, DROP COLUMN publish_time");
            jdbc.execute("ALTER TABLE tb_user_migration_test DROP COLUMN role");
            jdbc.execute("ALTER TABLE tb_follow_migration_test DROP INDEX idx_follow_author_id");
            jdbc.update("INSERT INTO tb_blog_migration_test (id,shop_id,user_id,title,images,content) VALUES (1,1,10,'历史','','正文')");
            String script = org.springframework.util.StreamUtils.copyToString(new ClassPathResource(
                    "db/migration/V20261104__blog_review_publish.sql").getInputStream(), StandardCharsets.UTF_8)
                    .replaceAll("\\btb_blog\\b", "tb_blog_migration_test")
                    .replaceAll("\\btb_user\\b", "tb_user_migration_test")
                    .replaceAll("\\btb_follow\\b", "tb_follow_migration_test")
                    .replaceAll("\\btb_blog_feed_outbox\\b", "tb_blog_feed_outbox_migration_test");
            new ResourceDatabasePopulator(new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8))).execute(dataSource);
            assertEquals(2, jdbc.queryForObject("SELECT status FROM tb_blog_migration_test WHERE id=1", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_migration_test WHERE publish_time=create_time", Integer.class));
            jdbc.update("INSERT INTO tb_blog_migration_test (id,shop_id,user_id,title,images,content) VALUES (2,1,10,'新草稿','','正文')");
            assertEquals(0, jdbc.queryForObject("SELECT status FROM tb_blog_migration_test WHERE id=2", Integer.class));
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS tb_blog_feed_outbox_migration_test, tb_blog_migration_test, tb_user_migration_test, tb_follow_migration_test");
        }
    }
}
