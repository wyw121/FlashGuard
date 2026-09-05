package com.flashguard.order;

import com.flashguard.shared.api.ApiResponse;
import com.flashguard.shared.error.BusinessCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderQueryService service;

    public OrderController(OrderQueryService service) {
        this.service = service;
    }

    @GetMapping("/{orderNo}")
    public ApiResponse<OrderView> findByOrderNo(@PathVariable String orderNo) {
        return ApiResponse.of(BusinessCode.SUCCESS, service.findByOrderNo(orderNo));
    }
}
