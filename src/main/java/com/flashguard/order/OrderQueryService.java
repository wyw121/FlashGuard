package com.flashguard.order;

import com.flashguard.shared.error.BusinessCode;
import com.flashguard.shared.error.BusinessException;
import org.springframework.stereotype.Service;

@Service
public class OrderQueryService {

    private final OrderMapper orderMapper;

    public OrderQueryService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    public OrderView findByOrderNo(String orderNo) {
        return orderMapper.findByOrderNo(orderNo)
            .map(OrderView::from)
            .orElseThrow(() -> new BusinessException(BusinessCode.ORDER_NOT_FOUND));
    }
}
