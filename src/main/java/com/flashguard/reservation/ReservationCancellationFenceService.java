package com.flashguard.reservation;

import com.flashguard.mq.ReservationMessageCodec;
import com.flashguard.order.OrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits independent cancellation ownership without modifying a different request's winning row. */
@Service
public class ReservationCancellationFenceService {
    private final StockReservationMapper mapper;
    private final StockReservationService durable;
    private final OrderMapper orders;
    public ReservationCancellationFenceService(StockReservationMapper mapper, StockReservationService durable, OrderMapper orders) {
        this.mapper = mapper; this.durable = durable; this.orders = orders;
    }
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public StockReservationRecord claim(PendingReservation pending) {
        if (pending.expireAt() > System.currentTimeMillis()) throw new IllegalStateException("Conflict reservation has not expired");
        // Same first lock as local MQ commit. A persisted fence wins over every later callback.
        var fence = mapper.findCancellationFenceForUpdate(pending.reservationId()).orElse(null);
        if (fence != null) {
            if (!ReservationMessageCodec.matches(pending, fence)) throw new IllegalStateException("Cancellation fence identity mismatch");
            return fence;
        }
        if (mapper.findByReservationIdForUpdate(pending.reservationId()).isPresent())
            throw new IllegalStateException("Existing reservation requires normal compensation");
        var winner = mapper.findByRequestId(pending.requestId()).orElseThrow(() ->
            new IllegalStateException("No durable conflicting request owner"));
        winner = mapper.findByReservationIdForUpdate(winner.reservationId()).orElseThrow();
        if (winner.reservationId().equals(pending.reservationId()) || !winner.requestId().equals(pending.requestId()))
            throw new IllegalStateException("Request owner changed");
        if (orders.findByReservationId(pending.reservationId()).isPresent())
            throw new IllegalStateException("Losing reservation already has an order");
        var requestOrder = orders.findByRequestId(pending.requestId()).orElse(null);
        if (requestOrder != null && requestOrder.activityId() == Long.parseLong(pending.activityId())
            && requestOrder.userId() == Long.parseLong(pending.userId()))
            throw new IllegalStateException("Losing identity already has a completed order");
        var record = durable.toPendingRecord(pending);
        fence = new StockReservationRecord(0, record.reservationId(), record.activityId(), record.userId(),
            record.requestId(), StockReservationStatus.CANCEL_PENDING, record.reservationId(), record.expireAt(),
            record.createdAt(),record.updatedAt());
        mapper.insertCancellationFence(fence);
        return fence;
    }
}
