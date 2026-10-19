package com.hmdp.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;

/** 建单进度与订单支付状态分开表达；success=true 仅代表本次接口调用成功。 */
@Data
@AllArgsConstructor
public class OrderResultDTO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;
    /** QUEUED、PROCESSING、SUCCESS、FAILED。SUCCESS 必须以数据库订单存在为准。 */
    private String state;
    private boolean canPay;
    /** 数据库订单状态，仅建单成功时存在。 */
    private Integer orderStatus;
    private String message;

    public static OrderResultDTO queued(Long orderId) {
        return new OrderResultDTO(orderId, "QUEUED", false, null,
                "请求已受理，正在排队，请查询最终下单结果");
    }
}
