package com.flashguard.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashguard.reservation.*;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.MDC;
import com.flashguard.shared.observability.CorrelationContext;

public class ReservationMessageCodec {
    private final ObjectMapper mapper;
    public ReservationMessageCodec(ObjectMapper mapper) { this.mapper = mapper; }
    public Message encode(String topic, PendingReservation pending) {
        try {
            Message message = new Message(topic, "RESERVATION", pending.reservationId(), mapper.writeValueAsBytes(pending));
            String traceId = MDC.get(CorrelationContext.TRACE_ID);
            if (traceId != null) message.putUserProperty("traceId", traceId);
            return message;
        } catch (Exception failure) { throw new IllegalArgumentException("Cannot encode reservation message", failure); }
    }
    public PendingReservation decode(Message message) {
        try {
            PendingReservation p = mapper.readValue(message.getBody(), PendingReservation.class);
            if (p.reservationId().isBlank() || p.requestId().isBlank() || Long.parseLong(p.activityId()) <= 0
                || Long.parseLong(p.userId()) <= 0 || p.status() != ReservationStatus.PENDING
                || !p.reservationId().equals(message.getKeys())) {
                throw new IllegalArgumentException("Invalid reservation message identity");
            }
            return p;
        } catch (Exception failure) { throw new IllegalArgumentException("Invalid reservation message", failure); }
    }
    public static boolean matches(PendingReservation p, StockReservationRecord r) {
        return p.reservationId().equals(r.reservationId()) && p.requestId().equals(r.requestId())
            && Long.parseLong(p.activityId()) == r.activityId() && Long.parseLong(p.userId()) == r.userId()
            && r.expireAt() != null && p.expireAt() == r.expireAt().toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
    }
}
