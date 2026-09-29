package com.flashguard.reservation;

import java.time.LocalDateTime;

public record StockReservationRecord(
    long id,
    String reservationId,
    long activityId,
    long userId,
    String requestId,
    StockReservationStatus status,
    String messageKey,
    LocalDateTime expireAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}

