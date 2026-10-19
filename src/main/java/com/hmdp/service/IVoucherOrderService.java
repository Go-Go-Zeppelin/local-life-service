package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    /** 查询当前用户的异步建单结果，只有数据库订单存在才返回 SUCCESS。 */
    Result queryOrderResult(Long orderId);

    void createVoucherOrder(VoucherOrder voucherOrder);

    /** 重试耗尽进入死信后，释放仍未落库的 Redis 预占。 */
    void releaseReservation(VoucherOrder voucherOrder, boolean allowProcessing);

    /** 死信处理前先核对数据库，避免“订单已提交但 ACK 丢失”时错误补库存。 */
    void handleDeadLetter(VoucherOrder voucherOrder);

    /** 重投 Lua 已登记但尚未被消费者领取的 Kafka 建单消息。 */
    int retryPendingMessages(int batchSize);

    boolean closeIfUnpaid(Long orderId);

    int closeExpiredOrders(int batchSize);

    int retryClosedOrderReleases(int batchSize);

    Result markPaid(Long orderId);
}
