package com.hmdp;

import com.hmdp.config.KafkaConfig;
import com.hmdp.dto.OrderResultDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 Kafka 的生产、消费、三次重试、DLT 和库存补偿闭环。 */
@SpringJUnitConfig({VoucherOrderFlowIntegrationTest.TestConfig.class,
        VoucherOrderKafkaIntegrationTest.MessagingConfig.class})
@TestPropertySource(properties = "spring.kafka.consumer.group-id=seckill-integration-test")
@EnabledIfEnvironmentVariable(named = "SECKILL_KAFKA_INTEGRATION_TEST", matches = "true")
class VoucherOrderKafkaIntegrationTest {
    @TestConfiguration
    @EnableKafka
    @Import(KafkaConfig.class)
    static class MessagingConfig {
        @Bean
        KafkaAdmin kafkaAdmin() {
            Map<String, Object> properties = new HashMap<>();
            properties.put("bootstrap.servers", "127.0.0.1:19092");
            return new KafkaAdmin(properties);
        }
        @Bean
        ConsumerFactory<Object, Object> consumerFactory() {
            Map<String, Object> properties = new HashMap<>();
            properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:19092");
            properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
            properties.put(JsonDeserializer.TRUSTED_PACKAGES, "com.hmdp.entity");
            return new DefaultKafkaConsumerFactory<>(properties);
        }
        @Bean
        ProducerFactory<Object, Object> producerFactory() {
            Map<String, Object> properties = new HashMap<>();
            properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:19092");
            properties.put(ProducerConfig.ACKS_CONFIG, "all");
            properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
            return new DefaultKafkaProducerFactory<>(properties);
        }
        @Bean
        KafkaTemplate<Object, Object> kafkaTemplate(ProducerFactory<Object, Object> factory) {
            return new KafkaTemplate<>(factory);
        }
    }

    @Autowired private IVoucherOrderService orders;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private DataSource dataSource;

    private void login(Long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
    }

    private OrderResultDTO awaitResult(Long id, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Result result = orders.queryOrderResult(id);
            if (Boolean.TRUE.equals(result.getSuccess())) {
                OrderResultDTO progress = (OrderResultDTO) result.getData();
                if (expected.equals(progress.getState())) { return progress; }
            }
            Thread.sleep(100);
        }
        fail("Kafka 消费未在期限内产生 " + expected + "，orderId=" + id);
        return null;
    }

    @Test
    void realKafkaCreatesOrdersAndRoutesExhaustedRetriesToCompensation() throws Exception {
        new ResourceDatabasePopulator(new ByteArrayResource(
                "SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION';".getBytes()),
                new ClassPathResource("db/hmdp.sql")).execute(dataSource);
        redis.execute(connection -> { connection.serverCommands().flushDb(); return null; }, true);
        jdbc.update("INSERT INTO tb_seckill_voucher (voucher_id,stock,begin_time,end_time) " +
                "VALUES (1,2,NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY))");
        redis.opsForValue().set("seckill:stock:1", "2");
        try {
            login(10L);
            OrderResultDTO accepted = (OrderResultDTO) orders.seckillVoucher(1L).getData();
            assertEquals("QUEUED", accepted.getState());
            assertFalse(accepted.isCanPay());
            assertTrue(awaitResult(accepted.getOrderId(), "SUCCESS").isCanPay());
            assertEquals(1, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class));
            assertEquals("1", redis.opsForValue().get("seckill:stock:1"));

            // 制造 Redis 有余量、MySQL 无库存，真实验证主消费者重试耗尽后进入 DLT。
            jdbc.update("UPDATE tb_seckill_voucher SET stock=0");
            login(11L);
            OrderResultDTO rejected = (OrderResultDTO) orders.seckillVoucher(1L).getData();
            assertFalse(awaitResult(rejected.getOrderId(), "FAILED").isCanPay());
            assertFalse(orders.markPaid(rejected.getOrderId()).getSuccess());
            assertEquals("1", redis.opsForValue().get("seckill:stock:1"));
            assertEquals(0, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
        } finally {
            UserHolder.removeUser();
        }
    }
}
