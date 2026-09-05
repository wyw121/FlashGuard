package com.flashguard.shared.error;

public enum BusinessCode {
    SUCCESS("Success"),
    REPLAY("Request already completed"),
    VALIDATION_ERROR("Request validation failed"),
    ACTIVITY_NOT_FOUND("Activity not found"),
    ACTIVITY_NOT_STARTED("Activity has not started"),
    ACTIVITY_ENDED("Activity has ended or is unavailable"),
    SOLD_OUT("Stock is sold out"),
    DUPLICATE_PURCHASE("User already participated in this activity"),
    PARTICIPATION_NOT_FOUND("Participation result not found"),
    ORDER_NOT_FOUND("Order not found"),
    INTERNAL_ERROR("Internal server error");

    private final String defaultMessage;

    BusinessCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
