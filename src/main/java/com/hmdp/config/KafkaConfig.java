package com.hmdp.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import java.util.HashMap;
import java.util.Map;

/** Kafka 秒杀订单消费的重试与死信配置。 */
@Configuration
public class KafkaConfig {

    /**
     * 本地单 Broker 默认使用一个副本；生产环境应使用多个 Broker，并通过配置将副本数和最小 ISR 调高。
     */
    @Value("${hmdp.kafka.seckill-order.partitions:3}")
    private int seckillOrderPartitions;

    @Value("${hmdp.kafka.seckill-order.replicas:1}")
    private int seckillOrderReplicas;

    @Value("${hmdp.kafka.seckill-order.min-insync-replicas:1}")
    private int seckillOrderMinInsyncReplicas;

    @Value("${hmdp.kafka.consumer.concurrency:3}")
    private int consumerConcurrency;

    @Bean
    public NewTopic seckillVoucherOrderTopic() {
        validateTopicSettings();
        return TopicBuilder.name("seckill-voucher-order")
                .partitions(seckillOrderPartitions)
                .replicas(seckillOrderReplicas)
                .configs(topicConfigs())
                .build();
    }

    @Bean
    public NewTopic seckillVoucherOrderDltTopic() {
        validateTopicSettings();
        return TopicBuilder.name("seckill-voucher-order.DLT")
                .partitions(seckillOrderPartitions)
                .replicas(seckillOrderReplicas)
                .configs(topicConfigs())
                .build();
    }

    private Map<String, String> topicConfigs() {
        Map<String, String> configs = new HashMap<>();
        configs.put(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG,
                String.valueOf(seckillOrderMinInsyncReplicas));
        // 没有 ISR 时宁可暂时不可写，也不选可能丢数据的非 ISR 副本。
        configs.put(TopicConfig.UNCLEAN_LEADER_ELECTION_ENABLE_CONFIG, "false");
        return configs;
    }

    private void validateTopicSettings() {
        if (seckillOrderPartitions < 1 || seckillOrderReplicas < 1
                || seckillOrderMinInsyncReplicas < 1
                || seckillOrderMinInsyncReplicas > seckillOrderReplicas) {
            throw new IllegalArgumentException(
                    "Kafka 秒杀 Topic 配置非法：需要 partitions >= 1、replicas >= 1、"
                            + "1 <= min.insync.replicas <= replicas");
        }
    }

    /**
     * 主消费者：业务异常最多重试 3 次，仍失败则发送到 seckill-voucher-order.DLT，
     * 并提交原消息 offset，避免分区被一条坏消息永久阻塞。
     */
    @Bean(name = "seckillVoucherOrderKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> seckillVoucherOrderKafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(Math.max(1, Math.min(consumerConcurrency, seckillOrderPartitions)));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                // 负分区让生产者自行选择，避免 DLT 分区数少于源 Topic 时补偿消息再次失败。
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", -1));
        // DLT 未确认写入时必须继续重试原消息，不能提前提交 offset 丢失补偿任务。
        recoverer.setFailIfSendResultIsError(true);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                // 1000ms 间隔，maxAttempts=3 表示初次消费失败后再重试 3 次。
                new FixedBackOff(1000L, 3L));
        errorHandler.setCommitRecovered(true);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    /** 死信消费者本身失败时不再递归发布 DLT；不 ACK 则保留消息等待人工恢复。 */
    @Bean(name = "seckillVoucherOrderDltKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> seckillVoucherOrderDltKafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(Math.max(1, Math.min(consumerConcurrency, seckillOrderPartitions)));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // DLT 是最后一道补偿防线；数据库或 Redis 暂时不可用时持续重试，绝不静默跳过。
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(5000L, Long.MAX_VALUE)));
        return factory;
    }
}
