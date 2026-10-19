package com.hmdp.config;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.admin.NewTopic;
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

/** Kafka 秒杀订单消费的重试与死信配置。 */
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic seckillVoucherOrderTopic() {
        return TopicBuilder.name("seckill-voucher-order").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic seckillVoucherOrderDltTopic() {
        return TopicBuilder.name("seckill-voucher-order.DLT").partitions(1).replicas(1).build();
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
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // DLT 是最后一道补偿防线；数据库或 Redis 暂时不可用时持续重试，绝不静默跳过。
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(5000L, Long.MAX_VALUE)));
        return factory;
    }
}
