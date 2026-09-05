package com.flashguard.order;

import java.time.LocalDateTime;

public record OrderRecord(
    long id,
    String orderNo,
    String requestId,
    long activityId,
    long userId,
    OrderStatus status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
