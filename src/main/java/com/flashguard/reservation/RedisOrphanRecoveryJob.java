package com.flashguard.reservation;

import com.flashguard.activity.ActivityMapper;
import com.flashguard.reconciliation.ReconciliationIssueMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Recovers the crash window before a Redis reservation has a durable DB row. */
@Service
public class RedisOrphanRecoveryJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisOrphanRecoveryJob.class);
    private static final int BATCH_SIZE = 200;
    private final ActivityMapper activities;
    private final RedisReservationStore redis;
    private final StockReservationService durable;
    private final ReservationTimeoutCompensationService compensation;
    private final ReconciliationIssueMapper issues;
    private final ConflictReservationRecoveryService conflictRecovery;
    private long activityCursor;
    private long reservationOffset;

    public RedisOrphanRecoveryJob(ActivityMapper activities, RedisReservationStore redis,
        StockReservationService durable, ReservationTimeoutCompensationService compensation,
        ReconciliationIssueMapper issues) {
        this(activities, redis, durable, compensation, issues, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RedisOrphanRecoveryJob(ActivityMapper activities, RedisReservationStore redis,
        StockReservationService durable, ReservationTimeoutCompensationService compensation,
        ReconciliationIssueMapper issues, ConflictReservationRecoveryService conflictRecovery) {
        this.conflictRecovery = conflictRecovery;
        this.activities = activities;
        this.redis = redis;
        this.durable = durable;
        this.compensation = compensation;
        this.issues = issues;
    }

    @Scheduled(fixedDelayString = "${flashguard.compensation.orphan-scan-interval:5s}")
    public synchronized void recover() {
        var ids = activities.findIdsAfter(activityCursor, 1);
        if (ids.isEmpty()) {
            activityCursor = 0;
            reservationOffset = 0;
            return;
        }
        long activityId = ids.get(0);
        try {
            var expired = redis.findExpiredRequestIds(activityId, System.currentTimeMillis(), reservationOffset, BATCH_SIZE);
            for (String requestId : expired) {
                try {
                    PendingReservation pending = redis.findByRequestId(activityId, requestId).orElse(null);
                    if (pending == null) continue;
                    StockReservationRecord record = durable.findByRequestId(pending.requestId()).orElse(null);
                    if (record == null) {
                        try {
                            record = durable.createPending(pending);
                        } catch (DuplicateKeyException conflict) {
                            record = durable.findByRequestId(pending.requestId()).orElse(null);
                        }
                    }
                    if (record == null || !record.reservationId().equals(pending.reservationId())
                        || record.activityId() != activityId || record.userId() != Long.parseLong(pending.userId())) {
                        if (record != null && !record.reservationId().equals(pending.reservationId()) && conflictRecovery != null) {
                            conflictRecovery.recoverConflict(pending);
                        } else {
                            issues.insert(activityId, "Orphan reservation identity conflict: " + pending.reservationId());
                        }
                        continue;
                    }
                    if (record.status() == StockReservationStatus.PENDING
                        || record.status() == StockReservationStatus.MQ_COMMITTED
                        || record.status() == StockReservationStatus.CANCEL_PENDING
                        || record.status() == StockReservationStatus.COMPENSATION_FAILED) {
                        compensation.compensateReservation(record);
                    }
                } catch (Exception failure) {
                    LOGGER.warn("Orphan recovery failed requestId={}", requestId, failure);
                    try {
                        issues.insert(activityId, "Orphan recovery failed requestId=" + requestId
                            + ", reason=" + failure.getClass().getSimpleName());
                    } catch (Exception auditFailure) {
                        LOGGER.warn("Cannot audit orphan requestId={}", requestId, auditFailure);
                    }
                }
            }
            if (expired.size() == BATCH_SIZE) {
                // Confirmed Redis entries remain in the zset. Page past them to avoid starvation.
                // Deletions may shift offsets; every completed activity sweep starts again at zero.
                reservationOffset += BATCH_SIZE;
            } else {
                activityCursor = activityId;
                reservationOffset = 0;
            }
        } catch (Exception failure) {
            LOGGER.warn("Redis orphan scan failed activityId={}", activityId, failure);
            try {
                issues.insert(activityId, "Orphan scan unavailable: " + failure.getClass().getSimpleName());
            } catch (Exception auditFailure) {
                LOGGER.warn("Cannot audit orphan scan activityId={}", activityId, auditFailure);
            }
            activityCursor = activityId;
            reservationOffset = 0;
        }
    }
}
