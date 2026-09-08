package com.flashguard.reservation;

public enum StockReservationStatus {
    PENDING,
    MQ_COMMITTED,
    CONFIRMED,
    CANCEL_PENDING,
    CANCELLED,
    COMPENSATION_FAILED
}

