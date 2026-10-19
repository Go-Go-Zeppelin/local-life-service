package com.hmdp.task;

import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 定期关闭超过支付期限仍未支付的订单。
 */
@Slf4j
@Component
public class VoucherOrderCloseTask {

    private static final int BATCH_SIZE = 500;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Scheduled(fixedDelayString = "${hmdp.order.close-interval-ms:60000}",
            initialDelayString = "${hmdp.order.close-initial-delay-ms:60000}")
    public void closeExpiredOrders() {
        try {
            int closed = voucherOrderService.closeExpiredOrders(BATCH_SIZE);
            if (closed > 0) {
                log.info("订单超时关单任务完成，关闭订单数={}", closed);
            }
        } catch (Exception e) {
            // 本轮失败不影响下一轮调度，未处理订单会再次被扫描。
            log.error("订单超时关单任务执行异常", e);
        }
        try {
            voucherOrderService.retryClosedOrderReleases(BATCH_SIZE);
        } catch (Exception e) {
            log.error("已关单订单的 Redis 库存释放重试失败", e);
        }
    }
}
