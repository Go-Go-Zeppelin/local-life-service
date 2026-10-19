package com.hmdp.service;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Arrays;

import static com.hmdp.utils.RedisConstants.*;

/** 关单后的 Redis 库存补偿；数据库保存待处理事实，Redis 标记保证回补幂等。 */
@Service
public class OrderStockReleaseService {
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>();
    static {
        RELEASE_SCRIPT.setLocation(new ClassPathResource("seckill-order-close-release.lua"));
        RELEASE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private VoucherOrderMapper orderMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // afterCommit 中的新数据库写入必须另开事务，否则可能使用已提交但尚未解绑的连接。
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseClosedOrder(Long orderId) {
        VoucherOrder order = orderMapper.selectForUpdate(orderId);
        if (order == null || !Integer.valueOf(4).equals(order.getStatus())
                || Boolean.TRUE.equals(order.getRedisStockReleased())) {
            return;
        }
        Long released = stringRedisTemplate.execute(RELEASE_SCRIPT,
                Arrays.asList(SECKILL_RESERVATION_KEY + orderId, SECKILL_STOCK_KEY + order.getVoucherId(),
                        SECKILL_CLOSED_RELEASE_KEY + orderId),
                String.valueOf(order.getVoucherId()), String.valueOf(order.getUserId()), String.valueOf(orderId));
        if (released == null || (released != 1L && released != 2L)) {
            throw new IllegalStateException("关单 Redis 库存释放失败");
        }
        if (orderMapper.markRedisStockReleased(orderId) != 1) {
            throw new IllegalStateException("记录 Redis 库存释放结果失败");
        }
    }
}
