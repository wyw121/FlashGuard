package com.flashguard.order;

import java.time.LocalDateTime;

public record OrderView(
    String orderNo,
    String requestId,
    long activityId,
    long userId,
    OrderStatus status,
    LocalDateTime createdAt
) {

    public static OrderView from(OrderRecord order) {
        return new OrderView(
            order.orderNo(),
            order.requestId(),
            order.activityId(),
            order.userId(),
            order.status(),
            order.createdAt()
        );
    }
}
