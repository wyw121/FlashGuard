package com.flashguard.reservation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Objects;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockReservationService {

    private static final String NO_MESSAGE_KEY = null;

    private final StockReservationMapper reservationMapper;

    public StockReservationService(StockReservationMapper reservationMapper) {
        this.reservationMapper = reservationMapper;
    }

    @Transactional
    public StockReservationRecord createPending(PendingReservation pendingReservation) {
        Objects.requireNonNull(pendingReservation, "pendingReservation");
        StockReservationRecord reservation = toPendingRecord(pendingReservation);
        reservationMapper.insert(reservation);
        return reservation;
    }

    public Optional<StockReservationRecord> findCancellationFence(String reservationId) {
        return reservationMapper.findCancellationFence(reservationId);
    }

    public Optional<StockReservationRecord> findByRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return reservationMapper.findByRequestId(requestId);
    }

    public Optional<StockReservationRecord> findByReservationIdForUpdate(String reservationId) {
        if (reservationId == null || reservationId.isBlank()) {
            return Optional.empty();
        }
        return reservationMapper.findByReservationIdForUpdate(reservationId);
    }

    public int markAsMqCommitted(String reservationId, String messageKey) {
        return reservationMapper.markAsMqCommitted(reservationId, messageKey);
    }

    public int markAsConfirmed(String reservationId, String messageKey) {
        return reservationMapper.markAsConfirmed(reservationId, messageKey);
    }

    public long countMqBacklog() {
        return reservationMapper.countMqBacklog();
    }

    public List<StockReservationRecord> findConsumable(int limit) {
        return reservationMapper.findConsumable(limit);
    }

    public List<StockReservationRecord> findExpiredConsumable(LocalDateTime cutoff, int limit) {
        return reservationMapper.findExpiredConsumable(cutoff, limit);
    }

    public int markAsCancelPending(String reservationId) {
        return markAsCancelPending(reservationId, null);
    }

    public int markAsCancelPending(String reservationId, String messageKey) {
        return reservationMapper.markAsCancelPending(reservationId, messageKey);
    }

    public int markAsCancelled(String reservationId, String messageKey) {
        return reservationMapper.markAsCancelled(reservationId, messageKey);
    }

    public int markAsCompensationFailed(String reservationId, String messageKey) {
        return reservationMapper.markAsCompensationFailed(reservationId, messageKey);
    }

    public long countByActivity(long activityId) {
        if (activityId <= 0) {
            return 0L;
        }
        return reservationMapper.countByActivity(activityId);
    }

    public long countOpenReservations(long activityId) {
        if (activityId <= 0) {
            return 0L;
        }
        return reservationMapper.countOpenReservations(activityId);
    }

    public long countConfirmedByActivity(long activityId) {
        if (activityId <= 0) {
            return 0L;
        }
        return reservationMapper.countConfirmedByActivity(activityId);
    }

    public long countConfirmedWithoutOrder(long activityId) {
        if (activityId <= 0) {
            return 0L;
        }
        return reservationMapper.countConfirmedWithoutOrder(activityId);
    }

    public StockReservationRecord toPendingRecord(PendingReservation pendingReservation) {
        Objects.requireNonNull(pendingReservation, "pendingReservation");
        long activityId = Long.parseLong(pendingReservation.activityId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new StockReservationRecord(
            0L,
            pendingReservation.reservationId(),
            activityId,
            Long.parseLong(pendingReservation.userId()),
            pendingReservation.requestId(),
            StockReservationStatus.PENDING,
            NO_MESSAGE_KEY,
            LocalDateTime.ofInstant(Instant.ofEpochMilli(pendingReservation.expireAt()), ZoneOffset.UTC),
            now,
            now
        );
    }
}
