package com.flashguard.reservation;

public class ReservationStoreException extends RuntimeException {

    public ReservationStoreException(String message) {
        super(message);
    }

    public ReservationStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
