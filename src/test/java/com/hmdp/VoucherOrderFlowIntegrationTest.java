package com.hmdp;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.hmdp.consumer.SeckillVoucherConsumer;
import com.hmdp.dto.OrderResultDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.OrderStockReleaseService;
import com.hmdp.service.impl.SeckillVoucherServiceImpl;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.util.concurrent.SettableListenableFuture;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用专用 MySQL 数据库及 Redis DB 15；显式启用，绝不连接项目默认业务库。 */
@SpringJUnitConfig({VoucherOrderFlowIntegrationTest.TestConfig.class,
        VoucherOrderFlowIntegrationTest.MockKafkaConfig.class})
@EnabledIfEnvironmentVariable(named = "SECKILL_INTEGRATION_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VoucherOrderFlowIntegrationTest {
    @TestConfiguration
    @EnableTransactionManagement
    @MapperScan("com.hmdp.mapper")
    @Import({VoucherOrderServiceImpl.class,
            SeckillVoucherServiceImpl.class, OrderStockReleaseService.class, RedisIdWorker.class,
            SeckillVoucherConsumer.class})
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:mysql://127.0.0.1:13306/seckill_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                    "root", "seckill-test");
        }
        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            return factory.getObject();
        }
        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean
        LettuceConnectionFactory redisConnectionFactory() {
            LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 16379);
            factory.setDatabase(15);
            return factory;
        }
        @Bean
        StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }
        @Bean(destroyMethod = "shutdown")
        RedissonClient redissonClient() {
            Config config = new Config();
            config.setThreads(2).setNettyThreads(2);
            config.useSingleServer().setAddress("redis://127.0.0.1:16379").setDatabase(15)
                    .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4);
            return Redisson.create(config);
        }
    }

    @TestConfiguration
    static class MockKafkaConfig {
        @Bean
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, VoucherOrder> kafkaTemplate() { return mock(KafkaTemplate.class); }
    }

    @Autowired private IVoucherOrderService orders;
    @Autowired private OrderStockReleaseService releases;
    @Autowired private SeckillVoucherConsumer consumer;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private KafkaTemplate<String, VoucherOrder> kafka;
    private final List<SettableListenableFuture<SendResult<String, VoucherOrder>>> sends = new ArrayList<>();
    private final List<VoucherOrder> messages = new ArrayList<>();

    @BeforeAll
    void schema() {
        // 仓库初始化 SQL 含历史零日期默认值；只在建表连接上关闭零日期限制，保留严格数值校验。
        new ResourceDatabasePopulator(new ByteArrayResource(
                "SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION';".getBytes()),
                new ClassPathResource("db/hmdp.sql")).execute(dataSource);
    }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM tb_voucher_order");
        jdbc.update("DELETE FROM tb_seckill_voucher");
        jdbc.update("INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) " +
                "VALUES (1, 1, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY))");
        redis.execute(connection -> { connection.serverCommands().flushDb(); return null; }, true);
        redis.opsForValue().set("seckill:stock:1", "1");
        login(10L);
        reset(kafka);
        sends.clear();
        messages.clear();
        when(kafka.send(eq("seckill-voucher-order"), any(VoucherOrder.class))).thenAnswer(invocation -> {
            SettableListenableFuture<SendResult<String, VoucherOrder>> future = new SettableListenableFuture<>();
            synchronized (messages) {
                messages.add(invocation.getArgument(1));
                sends.add(future);
            }
            return future;
        });
    }

    @AfterEach
    void clearUser() { UserHolder.removeUser(); }

    private void login(Long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        UserHolder.saveUser(user);
    }
    private OrderResultDTO progress(Long id) {
        Result result = orders.queryOrderResult(id);
        assertTrue(result.getSuccess(), result.getErrorMsg());
        return (OrderResultDTO) result.getData();
    }
    private VoucherOrder accept() {
        Result result = orders.seckillVoucher(1L);
        assertTrue(result.getSuccess(), result.getErrorMsg());
        OrderResultDTO accepted = (OrderResultDTO) result.getData();
        assertEquals("QUEUED", accepted.getState());
        assertFalse(accepted.isCanPay());
        return messages.get(messages.size() - 1);
    }
    private ConsumerRecord<String, VoucherOrder> record(VoucherOrder order) {
        return new ConsumerRecord<>("seckill-voucher-order", 0, 0L, null, order);
    }
    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=1", Integer.class);
    }
    private void assertStocks(int expectedRedis, int expectedDb) {
        assertEquals(String.valueOf(expectedRedis), redis.opsForValue().get("seckill:stock:1"));
        assertEquals(expectedDb, stock());
    }

    @Test
    void acceptedCannotPayUntilCommitAndPaymentIsIdempotent() {
        VoucherOrder order = accept();
        assertEquals("QUEUED", progress(order.getId()).getState());
        assertFalse(orders.markPaid(order.getId()).getSuccess());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
        Acknowledgment ack = mock(Acknowledgment.class);
        consumer.processMessage(record(order), ack);
        verify(ack).acknowledge();
        assertEquals("SUCCESS", progress(order.getId()).getState());
        assertTrue(progress(order.getId()).isCanPay());
        assertStocks(0, 0);
        assertTrue(orders.markPaid(order.getId()).getSuccess());
        assertTrue(orders.markPaid(order.getId()).getSuccess());
        assertFalse(progress(order.getId()).isCanPay());
        assertEquals(2, progress(order.getId()).getOrderStatus());
    }

    @Test
    void anotherUserCannotQueryOrPayQueuedOrCreatedOrders() {
        VoucherOrder order = accept();
        login(11L);
        assertFalse(orders.queryOrderResult(order.getId()).getSuccess());
        assertFalse(orders.markPaid(order.getId()).getSuccess());
        consumer.processMessage(record(order), mock(Acknowledgment.class));
        assertFalse(orders.queryOrderResult(order.getId()).getSuccess());
        assertFalse(orders.markPaid(order.getId()).getSuccess());
        login(10L);
        assertTrue(progress(order.getId()).isCanPay());
    }

    @Test
    void duplicateDeliveryAndDeadLetterAfterCommitDoNotChangeStock() {
        VoucherOrder order = accept();
        consumer.processMessage(record(order), mock(Acknowledgment.class));
        consumer.processMessage(record(order), mock(Acknowledgment.class));
        consumer.processDeadLetter(record(order), mock(Acknowledgment.class));
        assertStocks(0, 0);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
        assertEquals("SUCCESS", progress(order.getId()).getState());
        // 生产者回调晚于消费者：不能重新加入待投递队列。
        sends.get(0).set(null);
        assertNull(redis.opsForZSet().score("seckill:reservation:pending", order.getId().toString()));
        // 完成标记丢失也必须以数据库为准。
        redis.delete("seckill:reservation:" + order.getId());
        assertEquals("SUCCESS", progress(order.getId()).getState());
    }

    @Test
    void consumptionFailureIsPendingUntilCompensatedAndLateDeliveryIsIgnored() {
        jdbc.update("UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=1");
        VoucherOrder order = accept();
        Acknowledgment ack = mock(Acknowledgment.class);
        assertThrows(RuntimeException.class, () -> consumer.processMessage(record(order), ack));
        verifyNoInteractions(ack);
        assertEquals("PROCESSING", progress(order.getId()).getState());
        assertStocks(0, 0);
        consumer.processDeadLetter(record(order), mock(Acknowledgment.class));
        consumer.processDeadLetter(record(order), mock(Acknowledgment.class));
        assertEquals("FAILED", progress(order.getId()).getState());
        assertFalse(orders.markPaid(order.getId()).getSuccess());
        assertStocks(1, 0);
        consumer.processMessage(record(order), mock(Acknowledgment.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
        assertStocks(1, 0);
        assertNotEquals(order.getId(), accept().getId());
    }

    @Test
    void insertFailureRollsBackDatabaseStockBeforeDeadLetterReleasesRedis() {
        VoucherOrder order = accept();
        order.setPayType(999); // MySQL unsigned tinyint 溢出，真实触发事务内 INSERT 失败。
        assertThrows(RuntimeException.class, () -> consumer.processMessage(record(order), mock(Acknowledgment.class)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
        assertStocks(0, 1);
        consumer.processDeadLetter(record(order), mock(Acknowledgment.class));
        assertStocks(1, 1);
        assertEquals("FAILED", progress(order.getId()).getState());
    }

    @Test
    void failedSendIsRetriedAndUnclaimedQueueTimeoutBecomesFailure() {
        VoucherOrder order = accept();
        sends.get(0).setException(new IllegalStateException("broker unavailable"));
        assertEquals("QUEUED", progress(order.getId()).getState());
        redis.opsForZSet().add("seckill:reservation:pending", order.getId().toString(), 0);
        assertEquals(1, orders.retryPendingMessages(100));
        assertEquals(2, messages.size());
        redis.opsForHash().put("seckill:reservation:" + order.getId(), "createdAt", "0");
        redis.opsForZSet().add("seckill:reservation:pending", order.getId().toString(), 0);
        orders.retryPendingMessages(100);
        assertEquals("FAILED", progress(order.getId()).getState());
        assertTrue(progress(order.getId()).getMessage().contains("超时"));
        assertStocks(1, 1);
        consumer.processMessage(record(order), mock(Acknowledgment.class));
        assertStocks(1, 1);
    }

    @Test
    void queueTimeoutCannotReleaseAnAlreadyClaimedRequest() {
        jdbc.update("UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=1");
        VoucherOrder order = accept();
        assertThrows(RuntimeException.class, () -> consumer.processMessage(record(order), mock(Acknowledgment.class)));
        redis.opsForHash().put("seckill:reservation:" + order.getId(), "createdAt", "0");
        redis.opsForZSet().add("seckill:reservation:pending", order.getId().toString(), 0);
        orders.retryPendingMessages(100);
        assertEquals("PROCESSING", progress(order.getId()).getState());
        assertStocks(0, 0);
    }

    @Test
    void differentOrderIdForSamePurchaseIsFailureAndReturnsOnlyExtraReservation() {
        VoucherOrder original = accept();
        consumer.processMessage(record(original), mock(Acknowledgment.class));
        // 模拟购买标记丢失后产生了第二次预占，数据库业务唯一键仍然存在。
        redis.opsForSet().remove("seckill:order:1", "10");
        redis.opsForValue().set("seckill:stock:1", "1");
        VoucherOrder duplicate = accept();
        consumer.processMessage(record(duplicate), mock(Acknowledgment.class));
        assertEquals("FAILED", progress(duplicate.getId()).getState());
        assertEquals("SUCCESS", progress(original.getId()).getState());
        assertStocks(1, 0);
        assertTrue(redis.opsForSet().isMember("seckill:order:1", "10"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
    }

    @Test
    void closeRedisFailureIsDurablyRetriedAndCannotRefundTwice() {
        VoucherOrder order = accept();
        consumer.processMessage(record(order), mock(Acknowledgment.class));
        StringRedisTemplate offline = mock(StringRedisTemplate.class);
        when(offline.execute(any(), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("offline"));
        Object target = AopTestUtils.getUltimateTargetObject(releases);
        ReflectionTestUtils.setField(target, "stringRedisTemplate", offline);
        try {
            assertTrue(orders.closeIfUnpaid(order.getId()));
        } finally {
            ReflectionTestUtils.setField(target, "stringRedisTemplate", redis);
        }
        assertStocks(0, 1);
        assertEquals(0, jdbc.queryForObject("SELECT redis_stock_released FROM tb_voucher_order", Integer.class));
        assertFalse(progress(order.getId()).isCanPay());
        assertFalse(orders.markPaid(order.getId()).getSuccess());
        assertEquals(1, orders.retryClosedOrderReleases(100));
        assertStocks(1, 1);
        assertEquals(1, jdbc.queryForObject("SELECT redis_stock_released FROM tb_voucher_order", Integer.class));
        // 模拟 Redis 已释放、数据库确认未提交，且预占记录随后过期。
        jdbc.update("UPDATE tb_voucher_order SET redis_stock_released=0");
        redis.delete("seckill:reservation:" + order.getId());
        orders.retryClosedOrderReleases(100);
        assertStocks(1, 1);
        assertFalse(orders.closeIfUnpaid(order.getId()));
        assertTrue(redis.opsForSet().isMember("seckill:order:1", "10"));
    }

    @Test
    void missingReservationDoesNotPreventClosedOrderStockRelease() {
        VoucherOrder order = accept();
        consumer.processMessage(record(order), mock(Acknowledgment.class));
        redis.delete("seckill:reservation:" + order.getId());
        assertTrue(orders.closeIfUnpaid(order.getId()));
        assertStocks(1, 1);
        assertEquals(4, progress(order.getId()).getOrderStatus());
    }

    @Test
    void deadLetterRedisFailureDoesNotAcknowledgeOrPretendFailureIsFinal() {
        VoucherOrder order = accept();
        StringRedisTemplate offline = mock(StringRedisTemplate.class);
        when(offline.execute(any(), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("offline"));
        Object target = AopTestUtils.getUltimateTargetObject(orders);
        ReflectionTestUtils.setField(target, "stringRedisTemplate", offline);
        Acknowledgment ack = mock(Acknowledgment.class);
        try {
            assertThrows(RuntimeException.class, () -> consumer.processDeadLetter(record(order), ack));
            verifyNoInteractions(ack);
        } finally {
            ReflectionTestUtils.setField(target, "stringRedisTemplate", redis);
        }
        assertEquals("QUEUED", progress(order.getId()).getState());
        assertStocks(0, 1);
        consumer.processDeadLetter(record(order), ack);
        verify(ack).acknowledge();
        assertEquals("FAILED", progress(order.getId()).getState());
        assertStocks(1, 1);
    }

    @Test
    void redisCompletedFlagAloneCannotProveOrderExists() {
        VoucherOrder order = accept();
        redis.opsForHash().put("seckill:reservation:" + order.getId(), "state", "COMPLETED");
        assertFalse(orders.queryOrderResult(order.getId()).getSuccess());
        assertFalse(orders.markPaid(order.getId()).getSuccess());
    }

    @Test
    void migrationDoesNotRefundHistoricalClosedOrdersAndNewRowsUsePendingDefault() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS tb_order_migration_test");
        jdbc.execute("CREATE TABLE tb_order_migration_test LIKE tb_voucher_order");
        try {
            jdbc.execute("ALTER TABLE tb_order_migration_test DROP INDEX idx_order_stock_release, " +
                    "DROP COLUMN redis_stock_released");
            jdbc.update("INSERT INTO tb_order_migration_test (id,user_id,voucher_id,status) VALUES (1,10,1,4)");
            String migration = org.springframework.util.StreamUtils.copyToString(new ClassPathResource(
                    "db/migration/V20261018__async_order_result.sql").getInputStream(),
                    java.nio.charset.StandardCharsets.UTF_8).replace("tb_voucher_order", "tb_order_migration_test");
            new ResourceDatabasePopulator(new ByteArrayResource(migration.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8))).execute(dataSource);
            assertEquals(1, jdbc.queryForObject("SELECT redis_stock_released FROM tb_order_migration_test " +
                    "WHERE id=1", Integer.class));
            jdbc.update("INSERT INTO tb_order_migration_test (id,user_id,voucher_id) VALUES (2,11,1)");
            assertEquals(0, jdbc.queryForObject("SELECT redis_stock_released FROM tb_order_migration_test " +
                    "WHERE id=2", Integer.class));
        } finally {
            jdbc.execute("DROP TABLE tb_order_migration_test");
        }
    }

    @Test
    void concurrentRequestsForLastVoucherAcceptExactlyOne() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Result>> calls = new ArrayList<>();
            for (long user = 20; user < 40; user++) {
                final long id = user;
                calls.add(() -> {
                    login(id);
                    try { return orders.seckillVoucher(1L); }
                    finally { UserHolder.removeUser(); }
                });
            }
            int accepted = 0;
            for (Future<Result> result : pool.invokeAll(calls)) {
                if (result.get().getSuccess()) { accepted++; }
            }
            assertEquals(1, accepted);
            assertEquals(1, messages.size());
            consumer.processMessage(record(messages.get(0)), mock(Acknowledgment.class));
            assertStocks(0, 0);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }
}
