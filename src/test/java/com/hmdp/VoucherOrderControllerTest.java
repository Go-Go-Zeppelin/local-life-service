package com.hmdp;

import com.hmdp.controller.VoucherOrderController;
import com.hmdp.dto.OrderResultDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VoucherOrderControllerTest {
    private final IVoucherOrderService service = mock(IVoucherOrderService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        VoucherOrderController controller = new VoucherOrderController();
        ReflectionTestUtils.setField(controller, "voucherOrderService", service);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void acceptedResponseHasProgressLocationAndLosslessOrderId() throws Exception {
        long id = 9007199254740993L;
        when(service.seckillVoucher(1L)).thenReturn(Result.ok(OrderResultDTO.queued(id)));
        mvc.perform(post("/voucher-order/seckill/1"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/voucher-order/result/" + id))
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.data.orderId").value(String.valueOf(id)))
                .andExpect(jsonPath("$.data.state").value("QUEUED"))
                .andExpect(jsonPath("$.data.canPay").value(false));
    }

    @Test
    void rejectedRequestIsNotAcceptedAndResultEndpointIsAvailable() throws Exception {
        when(service.seckillVoucher(1L)).thenReturn(Result.fail("库存不足"));
        mvc.perform(post("/voucher-order/seckill/1"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.success").value(false));
        when(service.queryOrderResult(2L)).thenReturn(Result.ok(OrderResultDTO.queued(2L)));
        mvc.perform(get("/voucher-order/result/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("QUEUED"));
    }
}
