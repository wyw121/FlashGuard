package com.flashguard.mq;

import com.flashguard.reservation.*;
import java.time.*;
import org.springframework.transaction.annotation.Transactional;

/** Separate proxied bean: the broker callback only observes a decision after the DB transaction commits. */
public class ReservationLocalTransactionService {
    private final StockReservationMapper mapper;
    public ReservationLocalTransactionService(StockReservationMapper mapper) { this.mapper = mapper; }

    // Missing-row FOR UPDATE at REPEATABLE_READ gap-locks other new reservations.
    // Existing rows still lock at READ_COMMITTED; unique keys arbitrate missing-row races.
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public StockReservationRecord commit(PendingReservation p) {
        StockReservationRecord fence = mapper.findCancellationFenceForUpdate(p.reservationId()).orElse(null);
        if (fence != null) {
            if (!ReservationMessageCodec.matches(p, fence)) throw new IllegalStateException("Cancellation fence identity conflict");
            return fence;
        }
        StockReservationRecord row = mapper.findByReservationIdForUpdate(p.reservationId()).orElse(null);
        if (row == null) {
            var owner = mapper.findByRequestId(p.requestId()).orElse(null);
            if (owner != null && !owner.reservationId().equals(p.reservationId())) {
                // Common winner lock with independent conflict cancellation; never insert over another owner.
                mapper.findByReservationIdForUpdate(owner.reservationId()).orElseThrow();
                throw new IllegalStateException("Request already belongs to a different durable reservation");
            }
            row = newRecord(p, p.expireAt() <= System.currentTimeMillis()
                ? StockReservationStatus.CANCEL_PENDING : StockReservationStatus.PENDING);
            mapper.insert(row);
        }
        if (!ReservationMessageCodec.matches(p, row)) throw new IllegalStateException("Reservation identity conflict");
        if (row.status() == StockReservationStatus.PENDING) {
            if (mapper.markAsMqCommitted(p.reservationId(), p.reservationId()) != 1)
                throw new IllegalStateException("Reservation ownership changed");
            return mapper.findByReservationIdForUpdate(p.reservationId()).orElseThrow();
        }
        // Cancellation states are terminal for message production, including failed Redis compensation.
        return row;
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public StockReservationRecord fenceMissing(PendingReservation p) {
        StockReservationRecord fence = mapper.findCancellationFenceForUpdate(p.reservationId()).orElse(null);
        if (fence != null) {
            if (!ReservationMessageCodec.matches(p, fence)) throw new IllegalStateException("Cancellation fence identity conflict");
            return fence;
        }
        StockReservationRecord row = mapper.findByReservationIdForUpdate(p.reservationId()).orElse(null);
        if (row != null) return row;
        if (p.expireAt() > System.currentTimeMillis()) throw new IllegalStateException("Reservation has not expired");
        row = newRecord(p, StockReservationStatus.CANCEL_PENDING);
        mapper.insert(row);
        return row;
    }

    private StockReservationRecord newRecord(PendingReservation p, StockReservationStatus status) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new StockReservationRecord(0, p.reservationId(), Long.parseLong(p.activityId()),
            Long.parseLong(p.userId()), p.requestId(), status, p.reservationId(),
            LocalDateTime.ofInstant(Instant.ofEpochMilli(p.expireAt()), ZoneOffset.UTC), now, now);
    }
}
