package com.hmdp.task;

import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/** 重投 Lua 已原子登记、但尚未被 Kafka 消费者领取的秒杀建单消息。 */
@Slf4j
@Component
public class SeckillMessageRetryTask {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Scheduled(fixedDelayString = "${hmdp.order.message-retry-interval-ms:5000}",
            initialDelayString = "${hmdp.order.message-retry-initial-delay-ms:5000}")
    public void retryPendingMessages() {
        try {
            int attempted = voucherOrderService.retryPendingMessages(100);
            if (attempted > 0) {
                log.info("秒杀待投递消息重试完成: attempted={}", attempted);
            }
        } catch (Exception e) {
            // Redis/Kafka 恢复后下轮继续，待投递记录不会被删除。
            log.error("秒杀待投递消息重试任务失败", e);
        }
    }
}
