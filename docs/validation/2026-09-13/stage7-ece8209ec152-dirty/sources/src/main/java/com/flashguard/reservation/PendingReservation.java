package com.flashguard.reservation;

import java.util.Objects;

public record PendingReservation(
    String reservationId,
    String activityId,
    String userId,
    String requestId,
    ReservationStatus status,
    long createdAt,
    long expireAt
) {
    public PendingReservation {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(activityId, "activityId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
        if (createdAt < 0 || expireAt < createdAt) {
            throw new IllegalArgumentException("Invalid reservation timestamps");
        }
    }
}
