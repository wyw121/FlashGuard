package com.flashguard.mq;

import com.flashguard.reservation.PendingReservation;
import com.flashguard.reservation.StockReservationRecord;

@FunctionalInterface
public interface ReservationMessagePublisher {
    StockReservationRecord publish(PendingReservation reservation);
}
