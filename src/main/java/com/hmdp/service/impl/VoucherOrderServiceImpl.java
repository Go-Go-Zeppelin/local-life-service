package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.OrderResultDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.OrderStockReleaseService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.SECKILL_RESERVATION_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_RESERVATION_PENDING_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final int UNPAID_STATUS = 1;
    private static final int PAID_STATUS = 2;
    private static final int CANCELLED_STATUS = 4;

    @Value("${hmdp.order.unpaid-timeout-minutes:15}")
    private int unpaidTimeoutMinutes;

    @Value("${hmdp.order.queue-timeout-seconds:300}")
    private long queueTimeoutSeconds;

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private KafkaTemplate<String, VoucherOrder> kafkaTemplate;
    @Resource
    private OrderStockReleaseService orderStockReleaseService;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> RELEASE_STOCK_SCRIPT;
    private static final DefaultRedisScript<Long> CLAIM_RESERVATION_SCRIPT;
    private static final DefaultRedisScript<Long> COMPLETE_RESERVATION_SCRIPT;
    private static final DefaultRedisScript<Long> RESCHEDULE_MESSAGE_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        RELEASE_STOCK_SCRIPT = new DefaultRedisScript<>();
        RELEASE_STOCK_SCRIPT.setLocation(new ClassPathResource("seckill-reservation-release.lua"));
        RELEASE_STOCK_SCRIPT.setResultType(Long.class);

        CLAIM_RESERVATION_SCRIPT = new DefaultRedisScript<>();
        CLAIM_RESERVATION_SCRIPT.setLocation(new ClassPathResource("seckill-reservation-claim.lua"));
        CLAIM_RESERVATION_SCRIPT.setResultType(Long.class);

        COMPLETE_RESERVATION_SCRIPT = new DefaultRedisScript<>();
        COMPLETE_RESERVATION_SCRIPT.setLocation(new ClassPathResource("seckill-reservation-complete.lua"));
        COMPLETE_RESERVATION_SCRIPT.setResultType(Long.class);

        RESCHEDULE_MESSAGE_SCRIPT = new DefaultRedisScript<>();
        RESCHEDULE_MESSAGE_SCRIPT.setLocation(new ClassPathResource("seckill-message-reschedule.lua"));
        RESCHEDULE_MESSAGE_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId),
                String.valueOf(System.currentTimeMillis())
        );
        if (result == null) {
            return Result.fail("秒杀服务异常，请稍后重试");
        }
        // 2.判断结果
        if(result == 1L){
            return Result.fail("库存不足");
        }
        if(result == 2L){
            return Result.fail("重复下单");
        }
        if (result != 0L) {
            return Result.fail("秒杀服务异常，请稍后重试");
        }
        // 有购买资格
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);    // 订单ID
        voucherOrder.setUserId(userId); // 用户ID
        voucherOrder.setVoucherId(voucherId); // 优惠券ID

        // 3.指定Topic发送Kafka消息  【秒杀链路消息队列使用】-2
        // 发送对象，由 Kafka JsonSerializer 序列化；不要发送 Lombok 的 toString() 文本
        // 本次发送失败也不会丢单：Lua 已登记待投递记录，定时任务会继续重试。
        sendOrderMessage(voucherOrder);

        // 仅承诺已受理。即使消费者已经完成，首次响应也不把消息发送等同于建单成功。
        return Result.ok(OrderResultDTO.queued(orderId));
    }

    @Override
    public Result queryOrderResult(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        // 数据库是建单成功的依据，Redis 完成标记失败或过期不能掩盖已提交的订单。
        VoucherOrder order = getById(orderId);
        if (order != null) {
            return ownedOrderResult(order, userId);
        }
        Map<Object, Object> reservation = stringRedisTemplate.opsForHash()
                .entries(SECKILL_RESERVATION_KEY + orderId);
        // 在读取 Redis 期间消费者可能刚提交，重新查库避免完成标记先于本次查询可见。
        order = getById(orderId);
        if (order != null) {
            return ownedOrderResult(order, userId);
        }
        if (!String.valueOf(userId).equals(reservation.get("userId"))) {
            return Result.fail("请求不存在或处理记录已过期");
        }
        Object state = reservation.get("state");
        if ("RESERVED".equals(state)) {
            return Result.ok(OrderResultDTO.queued(orderId));
        }
        if ("PROCESSING".equals(state)) {
            return Result.ok(new OrderResultDTO(orderId, "PROCESSING", false, null,
                    "订单正在处理中，请稍后查询"));
        }
        if ("RELEASED".equals(state)) {
            String reason = String.valueOf(reservation.get("failureReason"));
            String message = "QUEUE_TIMEOUT".equals(reason) ? "排队超时，库存已释放，请重新尝试"
                    : "ALREADY_PURCHASED".equals(reason) ? "已存在该优惠券的订单，本次请求未建单"
                    : "订单创建失败，库存已释放，请重新尝试";
            return Result.ok(new OrderResultDTO(orderId, "FAILED", false, null, message));
        }
        // 不根据 Redis 的 COMPLETED 就承诺成功，也不在事实不明时错误宣布失败。
        return Result.fail("订单状态暂时无法确认，请稍后查询");
    }

    private Result ownedOrderResult(VoucherOrder order, Long userId) {
        if (!userId.equals(order.getUserId())) {
            return Result.fail("请求不存在或处理记录已过期");
        }
        boolean canPay = Integer.valueOf(UNPAID_STATUS).equals(order.getStatus());
        return Result.ok(new OrderResultDTO(order.getId(), "SUCCESS", canPay, order.getStatus(),
                canPay ? "订单创建成功，可以支付" : "订单已创建，请查看订单状态"));
    }

    /**
     * 尝试把预占订单发送到 Kafka。无论本次是否成功，Lua 创建的待投递记录都不会丢失；
     * 消费者领取前由定时任务继续重投，因此调用方可以安全返回已受理的 orderId。
     */
    private void sendOrderMessage(VoucherOrder order) {
        try {
            kafkaTemplate.send("seckill-voucher-order", order).addCallback(
                    result -> rescheduleMessage(order, 30000L),
                    error -> {
                        log.error("秒杀订单消息发送失败，保留待重投记录: orderId={}", order.getId(), error);
                        rescheduleMessage(order, 5000L);
                    });
        } catch (Exception e) {
            log.error("秒杀订单消息发送失败，已保留待重投记录: orderId={}, userId={}, voucherId={}",
                    order.getId(), order.getUserId(), order.getVoucherId(), e);
            rescheduleMessage(order, 5000L);
        }
    }

    private void rescheduleMessage(VoucherOrder order, long delayMillis) {
        try {
            stringRedisTemplate.execute(RESCHEDULE_MESSAGE_SCRIPT,
                    Arrays.asList(SECKILL_RESERVATION_KEY + order.getId(), SECKILL_RESERVATION_PENDING_KEY),
                    String.valueOf(order.getId()), String.valueOf(System.currentTimeMillis() + delayMillis));
        } catch (Exception e) {
            // 初始待投递记录由 Lua 写入；回调失败不会删除它。
            log.error("更新秒杀消息重投时间失败: orderId={}", order.getId(), e);
        }
    }

    @Override
    public int retryPendingMessages(int batchSize) {
        Set<String> orderIds = stringRedisTemplate.opsForZSet().rangeByScore(
                SECKILL_RESERVATION_PENDING_KEY, 0, System.currentTimeMillis(), 0, batchSize);
        if (orderIds == null || orderIds.isEmpty()) {
            return 0;
        }

        int attempted = 0;
        for (String orderIdText : orderIds) {
            String reservationKey = SECKILL_RESERVATION_KEY + orderIdText;
            Map<Object, Object> reservation = stringRedisTemplate.opsForHash().entries(reservationKey);
            Object state = reservation.get("state");
            if (!"RESERVED".equals(state)) {
                stringRedisTemplate.opsForZSet().remove(SECKILL_RESERVATION_PENDING_KEY, orderIdText);
                continue;
            }

            try {
                VoucherOrder order = new VoucherOrder();
                order.setId(Long.valueOf(orderIdText));
                order.setVoucherId(Long.valueOf(String.valueOf(reservation.get("voucherId"))));
                order.setUserId(Long.valueOf(String.valueOf(reservation.get("userId"))));
                Object createdAt = reservation.get("createdAt");
                if (createdAt != null && System.currentTimeMillis() - Long.parseLong(createdAt.toString())
                        >= queueTimeoutSeconds * 1000L) {
                    expireQueuedReservation(order);
                } else {
                    sendOrderMessage(order);
                }
                attempted++;
            } catch (RuntimeException e) {
                // 数据损坏不能静默删除，保留记录并持续告警，避免错误补库存。
                log.error("秒杀待重投记录格式错误: orderId={}, reservation={}",
                        orderIdText, reservation, e);
            }
        }
        return attempted;
    }

    private void expireQueuedReservation(VoucherOrder order) {
        RLock lock = redissonClient.getLock("lock:order:" + order.getUserId());
        if (!lock.tryLock()) {
            return;
        }
        try {
            if (getById(order.getId()) != null) {
                completeReservation(order.getId());
            } else {
                // Lua 只释放仍未领取的 RESERVED，不能把正在落库的请求当作超时失败。
                releaseReservation(order, false, "QUEUE_TIMEOUT", hasPurchased(order));
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean hasPurchased(VoucherOrder order) {
        return query().eq("user_id", order.getUserId()).eq("voucher_id", order.getVoucherId()).count() > 0;
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断
        if (!isLock) {
            // 不能直接确认一条尚未处理的消息，抛异常交给 Kafka 重试
            throw new IllegalStateException("订单正在处理中，请稍后重试");
        }

        boolean unlockAfterCompletion = false;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 锁必须覆盖事务提交/回滚，避免死信补偿在“方法返回但事务尚未提交”的缝隙误释放库存。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (redisLock.isHeldByCurrentThread()) {
                        redisLock.unlock();
                    }
                }
            });
            unlockAfterCompletion = true;
        }

        try {
            // 幂等检查必须在扣库存之前，避免重复消费再次扣减库存
            if (getById(voucherOrder.getId()) != null) {
                log.info("订单已处理，忽略重复消息: orderId={}", voucherOrder.getId());
                completeReservation(voucherOrder.getId());
                return;
            }
            // 即使订单 ID 异常变化，也用业务唯一键兜底保证一人一单
            if (hasPurchased(voucherOrder)) {
                // 另一笔订单的存在不能证明当前 orderId 建单成功；退回本次多占的库存。
                releaseReservation(voucherOrder, true, "ALREADY_PURCHASED", true);
                return;
            }

            Long claimResult = claimReservation(voucherOrder);
            if (claimResult == null || claimResult == 0L) {
                throw new IllegalStateException("秒杀预占记录不存在，无法安全建单");
            }
            if (claimResult == 3L) {
                throw new IllegalStateException("Redis 已完成但数据库订单不存在，需核对数据");
            }
            if (claimResult == 4L) {
                // 消息已被死信补偿释放，迟到或重复投递不能再次扣库存。
                log.warn("忽略已释放的迟到秒杀消息: orderId={}", voucherOrder.getId());
                return;
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // Redis 已判定有资格，但数据库扣减失败时不能确认消息，应进入重试/告警流程
                throw new IllegalStateException("数据库库存不足或库存数据不一致");
            }
            // 7.创建订单
            if (!save(voucherOrder)) {
                throw new IllegalStateException("订单写入数据库失败");
            }
            // DB 事务提交后再标记完成，避免 DB 回滚而 Redis 误认为已落库。
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        completeReservation(voucherOrder.getId());
                    }
                });
            } else {
                completeReservation(voucherOrder.getId());
            }
        } finally {
            // 非事务调用时兜底释放；正常事务由 afterCompletion 在提交/回滚后释放。
            if (!unlockAfterCompletion && redisLock.isHeldByCurrentThread()) {
                redisLock.unlock();
            }
        }
    }

    private Long claimReservation(VoucherOrder order) {
        return stringRedisTemplate.execute(CLAIM_RESERVATION_SCRIPT,
                Collections.singletonList(SECKILL_RESERVATION_KEY + order.getId()),
                String.valueOf(order.getId()), String.valueOf(order.getVoucherId()), String.valueOf(order.getUserId()));
    }

    private void completeReservation(Long orderId) {
        try {
            stringRedisTemplate.execute(COMPLETE_RESERVATION_SCRIPT,
                    Collections.singletonList(SECKILL_RESERVATION_KEY + orderId), String.valueOf(orderId));
        } catch (Exception e) {
            // DB 已提交时不能回滚；保留状态并告警，供对账任务处理。
            log.error("标记秒杀预占完成失败: orderId={}", orderId, e);
        }
    }

    /**
     * 释放未落库订单的 Redis 预占。allowProcessing=true 仅供 DLT 在重试耗尽后调用。
     */
    @Override
    public void releaseReservation(VoucherOrder order, boolean allowProcessing) {
        releaseReservation(order, allowProcessing, "CREATE_FAILED", false);
    }

    private void releaseReservation(VoucherOrder order, boolean allowProcessing, String reason, boolean keepPurchaseMarker) {
        try {
            Long result = stringRedisTemplate.execute(RELEASE_STOCK_SCRIPT,
                    Arrays.asList(SECKILL_RESERVATION_KEY + order.getId(), SECKILL_STOCK_KEY),
                    allowProcessing ? "1" : "0", String.valueOf(order.getId()), reason,
                    keepPurchaseMarker ? "1" : "0");
            if (result == null || result == 0L || result == 3L) {
                throw new IllegalStateException("秒杀预占不存在或状态不允许释放");
            }
            log.info("秒杀预占释放完成: orderId={}, result={}", order.getId(), result);
        } catch (Exception e) {
            log.error("秒杀预占释放失败，需人工/定时补偿: orderId={}, voucherId={}, userId={}",
                    order.getId(), order.getVoucherId(), order.getUserId(), e);
            throw e;
        }
    }

    @Override
    public void handleDeadLetter(VoucherOrder order) {
        RLock lock = redissonClient.getLock("lock:order:" + order.getUserId());
        if (!lock.tryLock()) {
            // 可能有另一条重复消息正在落库，不能与它并发释放库存。
            throw new IllegalStateException("订单仍在处理中，暂不能执行死信补偿");
        }
        try {
            // 先以数据库事实为准：事务可能已经提交，只是消费者在 ACK 前崩溃或 Redis 完成标记失败。
            if (getById(order.getId()) != null) {
                completeReservation(order.getId());
                log.warn("死信对应订单已存在，仅修正预占状态: orderId={}", order.getId());
                return;
            }
            boolean purchased = hasPurchased(order);
            releaseReservation(order, true, purchased ? "ALREADY_PURCHASED" : "CREATE_FAILED", purchased);
            log.error("秒杀消息重试耗尽，已释放 Redis 预占: orderId={}, userId={}, voucherId={}",
                    order.getId(), order.getUserId(), order.getVoucherId());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional
    public int closeExpiredOrders(int batchSize) {
    /*
        定时任务
          ↓
        closeExpiredOrders：批量查询超时订单
          ↓
        closeIfUnpaid：关闭单笔订单
          ↓
        状态 1 → 4
          ↓
        恢复 MySQL 库存
          ↓
        事务提交后恢复 Redis 库存
     */
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(unpaidTimeoutMinutes);
        List<VoucherOrder> orders = baseMapper.selectExpiredUnpaid(deadline, batchSize);
        int closed = 0;
        for (VoucherOrder order : orders) {
            if (closeIfUnpaid(order.getId())) {
                closed++;
            }
        }
        return closed;
    }

    /**
     * 关闭单笔订单。订单状态和 MySQL 库存在同一事务中更新，Redis 在提交后恢复。
     */
    @Override
    @Transactional
    public boolean closeIfUnpaid(Long orderId) {
        VoucherOrder order = getById(orderId);
        if (order == null || order.getStatus() == null || order.getStatus() != UNPAID_STATUS) {
            return false;
        }

        // status=1 是支付回调与关单任务之间的乐观锁条件。
        int updated = baseMapper.closeIfUnpaid(orderId);
        if (updated != 1) {
            return false;
        }

        // 更新成功后，恢复 MySQL 库存：
        boolean stockUpdated = seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        if (!stockUpdated) {
            throw new IllegalStateException("订单已关闭，但 MySQL 库存释放失败，事务将回滚");
        }

        // 数据库事务提交后再恢复 Redis，避免数据库回滚而 Redis 库存先增加。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    releaseRedisReservation(order);
                }
            });
        } else {
            releaseRedisReservation(order);
        }
        return true;
    }

    /**
     * 示例支付回调的状态处理。真实接入第三方支付时，调用前必须校验签名、金额和商户订单号。
     */
    @Override
    @Transactional
    public Result markPaid(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = baseMapper.selectOwnedForUpdate(orderId, userId);
        if (order == null) {
            Result result = queryOrderResult(orderId);
            if (!Boolean.TRUE.equals(result.getSuccess())) {
                return result;
            }
            OrderResultDTO progress = (OrderResultDTO) result.getData();
            return Result.fail("FAILED".equals(progress.getState())
                    ? progress.getMessage() + "，不能支付" : "订单仍在排队或处理中，暂不能支付");
        }
        int updated = baseMapper.markPaid(orderId);
        if (updated == 1) {
            // 可在此处发放权益；涉及外部系统时建议使用 Outbox/可靠消息保证重试。
            return Result.ok();
        }

        if (order.getStatus() != null && order.getStatus() == PAID_STATUS) {
            // 第三方重复回调，幂等返回成功。
            return Result.ok();
        }
        if (order.getStatus() != null && order.getStatus() == CANCELLED_STATUS) {
            // 关单已释放库存，不能把终止态改回已支付；应发起原路退款。
            return Result.fail("订单已关闭，请原路退款");
        }
        return Result.fail("订单状态不允许支付");
    }

    private void releaseRedisReservation(VoucherOrder order) {
        try {
            orderStockReleaseService.releaseClosedOrder(order.getId());
        } catch (Exception e) {
            // 数据库 status=4 且 redis_stock_released=0 是持久化的重试依据。
            log.error("Redis 秒杀库存释放失败，orderId={}, voucherId={}, userId={}",
                    order.getId(), order.getVoucherId(), order.getUserId(), e);
        }
    }

    @Override
    public int retryClosedOrderReleases(int batchSize) {
        List<VoucherOrder> orders = baseMapper.selectPendingStockReleases(batchSize);
        for (VoucherOrder order : orders) {
            releaseRedisReservation(order);
        }
        return orders.size();
    }
}
