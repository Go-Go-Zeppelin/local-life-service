package com.hmdp.consumer;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class SeckillVoucherConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 【秒杀链路消息队列使用】-3
     * Kafka 监听消费秒杀订单消息
     * 指定之前创建的containerFactory
     */
    @KafkaListener(
            topics = "seckill-voucher-order",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "seckillVoucherOrderKafkaListenerContainerFactory"
    )
    public void processMessage(ConsumerRecord<String, VoucherOrder> record, Acknowledgment ack) {
        try {
            // 获取消息内的数据
            VoucherOrder order = record.value();
            // 生成订单 扣减库存 等等操作
            voucherOrderService.createVoucherOrder(order);
            // 业务成功后提交确认ack
            ack.acknowledge();
        } catch (Exception e) {
            log.error("消费异常: topic={}, offset={}, 原因={}", record.topic(), record.offset(), e.getMessage());
            // 不要 acknowledge(), 直接让异常向上抛出, 让 errorHandler 处理
            throw new RuntimeException("秒杀订单消费失败", e);
        }
    }

    /**
     * 主消费者重试耗尽后的补偿：此时确认消息无法落库，原子恢复 Redis 库存并移除购买占位。
     */
    @KafkaListener(
            topics = "seckill-voucher-order.DLT",
            groupId = "${spring.kafka.consumer.group-id}-dlt",
            containerFactory = "seckillVoucherOrderDltKafkaListenerContainerFactory"
    )
    public void processDeadLetter(ConsumerRecord<String, VoucherOrder> record, Acknowledgment ack) {
        VoucherOrder order = record.value();
        voucherOrderService.handleDeadLetter(order);
        ack.acknowledge();
    }
}
