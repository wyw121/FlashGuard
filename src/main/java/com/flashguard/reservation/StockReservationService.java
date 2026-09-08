package com.flashguard.reservation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Objects;

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
