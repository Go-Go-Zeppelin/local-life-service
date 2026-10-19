package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.dto.OrderResultDTO;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.net.URI;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    public ResponseEntity<Result> seckillVoucher(@PathVariable("id") Long voucherId) {
        Result result = voucherOrderService.seckillVoucher(voucherId);
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            return ResponseEntity.ok(result);
        }
        OrderResultDTO accepted = (OrderResultDTO) result.getData();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/voucher-order/result/" + accepted.getOrderId()))
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(result);
    }

    @GetMapping("/result/{id}")
    public Result queryOrderResult(@PathVariable("id") Long orderId) {
        return voucherOrderService.queryOrderResult(orderId);
    }

    /**
     * 示例支付回调入口。真实环境还应在进入服务层前校验第三方签名、金额和商户订单号。
     */
    @PostMapping("/pay/{id}")
    public Result pay(@PathVariable("id") Long orderId) {
        return voucherOrderService.markPaid(orderId);
    }
}
