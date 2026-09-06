package com.flashguard.reservation;

public class ReservationStateException extends ReservationStoreException {

    public ReservationStateException(String message) {
        super(message);
    }

    public ReservationStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
