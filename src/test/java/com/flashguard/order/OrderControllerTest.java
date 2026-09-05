package com.flashguard.order;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import com.flashguard.shared.error.BusinessCode;
import com.flashguard.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderQueryService service;

    @Test
    void returnsOrderByPublicOrderNumber() throws Exception {
        when(service.findByOrderNo("order-1")).thenReturn(new OrderView(
            "order-1",
            "request-1",
            1001L,
            2001L,
            OrderStatus.CONFIRMED,
            LocalDateTime.of(2026, 9, 5, 4, 0)
        ));

        mockMvc.perform(get("/api/v1/orders/order-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.orderNo").value("order-1"))
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.id").doesNotExist());
    }

    @Test
    void unknownOrderReturnsNotFound() throws Exception {
        when(service.findByOrderNo("missing"))
            .thenThrow(new BusinessException(BusinessCode.ORDER_NOT_FOUND));

        mockMvc.perform(get("/api/v1/orders/missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }
}
