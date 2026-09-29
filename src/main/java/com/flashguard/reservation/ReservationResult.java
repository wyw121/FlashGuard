package com.flashguard.reservation;

import java.util.Objects;

public record ReservationResult(
    ReservationDecision decision,
    PendingReservation reservation
) {
    public ReservationResult {
        Objects.requireNonNull(decision, "decision");
        boolean needsReservation = decision == ReservationDecision.SUCCESS
            || decision == ReservationDecision.REPLAY;
        if (needsReservation != (reservation != null)) {
            throw new IllegalArgumentException("Reservation does not match decision " + decision);
        }
    }

    public static ReservationResult withoutReservation(ReservationDecision decision) {
        return new ReservationResult(decision, null);
    }
}
