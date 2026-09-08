package com.flashguard.shared.observability;

import org.slf4j.MDC;

public final class CorrelationContext {

    public static final String TRACE_ID = "traceId";
    public static final String REQUEST_ID = "requestId";
    public static final String RESERVATION_ID = "reservationId";
    public static final String MESSAGE_KEY = "messageKey";

    private CorrelationContext() {
        // utility class
    }

    public static void setTraceId(String traceId) {
        if (isPresent(traceId)) {
            MDC.put(TRACE_ID, traceId);
        }
    }

    public static void setRequestId(String requestId) {
        if (isPresent(requestId)) {
            MDC.put(REQUEST_ID, requestId);
        }
    }

    public static void setReservationId(String reservationId) {
        if (isPresent(reservationId)) {
            MDC.put(RESERVATION_ID, reservationId);
        }
    }

    public static void setMessageKey(String messageKey) {
        if (isPresent(messageKey)) {
            MDC.put(MESSAGE_KEY, messageKey);
        }
    }

    public static void clearTraceId() {
        MDC.remove(TRACE_ID);
    }

    public static void clearRequestId() {
        MDC.remove(REQUEST_ID);
    }

    public static void clearReservationId() {
        MDC.remove(RESERVATION_ID);
    }

    public static void clearMessageKey() {
        MDC.remove(MESSAGE_KEY);
    }

    public static void clearAll() {
        clearTraceId();
        clearRequestId();
        clearReservationId();
        clearMessageKey();
    }

    public static void clearBusinessContext() {
        clearRequestId();
        clearReservationId();
        clearMessageKey();
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
