package com.hmdp.mapper;

import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @since 2021-12-22
 */
public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

    /** 当前读并加锁，避免支付与超时关单并发时依赖旧快照判断状态。 */
    @Select("SELECT * FROM tb_voucher_order WHERE id = #{orderId} AND user_id = #{userId} FOR UPDATE")
    VoucherOrder selectOwnedForUpdate(@Param("orderId") Long orderId, @Param("userId") Long userId);

    @Select("SELECT * FROM tb_voucher_order WHERE id = #{orderId} FOR UPDATE")
    VoucherOrder selectForUpdate(@Param("orderId") Long orderId);

    @Select("SELECT * FROM tb_voucher_order WHERE status = 4 AND redis_stock_released = 0 " +
            "ORDER BY id LIMIT #{batchSize}")
    List<VoucherOrder> selectPendingStockReleases(@Param("batchSize") int batchSize);

    @Update("UPDATE tb_voucher_order SET redis_stock_released = 1 " +
            "WHERE id = #{orderId} AND status = 4 AND redis_stock_released = 0")
    int markRedisStockReleased(@Param("orderId") Long orderId);

    @Select("SELECT * FROM tb_voucher_order " +
            "WHERE status = 1 AND create_time < #{deadline} " +
            "ORDER BY id LIMIT #{batchSize}")
    List<VoucherOrder> selectExpiredUnpaid(@Param("deadline") LocalDateTime deadline, @Param("batchSize") int batchSize);

    /** 只有状态仍为未支付时才能关闭，作为支付/关单的乐观锁。 */
    @Update("UPDATE tb_voucher_order SET status = 4, redis_stock_released = 0, update_time = NOW() " +
            "WHERE id = #{orderId} AND status = 1")
    int closeIfUnpaid(@Param("orderId") Long orderId);

    @Update("UPDATE tb_voucher_order SET status = 2, pay_time = NOW(), update_time = NOW() " +
            "WHERE id = #{orderId} AND status = 1")
    int markPaid(@Param("orderId") Long orderId);

}
