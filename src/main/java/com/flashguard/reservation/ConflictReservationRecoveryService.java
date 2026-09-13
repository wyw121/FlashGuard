package com.flashguard.reservation;

import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.LoggerFactory;

/** The DB fence survives loss of the Redis pending entry, so release-to-completion crashes remain recoverable. */
@Service
public class ConflictReservationRecoveryService {
    private final ReservationCancellationFenceService fences;
    private final StockReservationMapper mapper;
    private final RedisReservationStore redis;
    public ConflictReservationRecoveryService(ReservationCancellationFenceService fences, StockReservationMapper mapper,
        RedisReservationStore redis) {this.fences=fences;this.mapper=mapper;this.redis=redis;}

    public void recoverConflict(PendingReservation pending) {
        var fence = fences.claim(pending); // Proxy returns only after durable commit.
        release(fence);
    }

    @Scheduled(fixedDelayString = "${flashguard.compensation.fence-scan-interval:5s}")
    public void retryPendingFences() {
        for (var fence : mapper.findPendingCancellationFences(200)) {
            try { release(fence); }
            catch (Exception failure) {
                LoggerFactory.getLogger(getClass()).warn("Conflict cancellation retry failed reservationId={}",fence.reservationId(),failure);
                try { mapper.touchCancellationFence(fence.reservationId()); }
                catch (Exception unavailable) { /* Retain CANCEL_PENDING for the next successful DB scan. */ }
            }
        }
    }

    private void release(StockReservationRecord fence) {
        if (fence.status() == StockReservationStatus.CANCELLED) return;
        var pending = new PendingReservation(fence.reservationId(),Long.toString(fence.activityId()),Long.toString(fence.userId()),
            fence.requestId(),ReservationStatus.PENDING,0,fence.expireAt().toInstant(ZoneOffset.UTC).toEpochMilli());
        if (redis.releaseExpected(pending)) mapper.completeCancellationFence(fence.reservationId());
        else mapper.touchCancellationFence(fence.reservationId());
    }
}
