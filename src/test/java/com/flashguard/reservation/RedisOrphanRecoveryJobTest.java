package com.flashguard.reservation;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.flashguard.activity.ActivityMapper;
import com.flashguard.reconciliation.ReconciliationIssueMapper;
import org.junit.jupiter.api.Test;

class RedisOrphanRecoveryJobTest {
    @Test
    void createsDurableEvidenceBeforeCancellingAnExpiredRedisOnlyReservation() {
        ActivityMapper activities = mock(ActivityMapper.class);
        RedisReservationStore redis = mock(RedisReservationStore.class);
        StockReservationService durable = mock(StockReservationService.class);
        ReservationTimeoutCompensationService compensation = mock(ReservationTimeoutCompensationService.class);
        ReconciliationIssueMapper issues = mock(ReconciliationIssueMapper.class);
        PendingReservation pending = new PendingReservation("orphan", "1", "2", "request", ReservationStatus.PENDING, 1, 2);
        StockReservationRecord record = new StockReservationRecord(1, "orphan", 1, 2, "request",
            StockReservationStatus.PENDING, null, LocalDateTime.now().minusMinutes(1), LocalDateTime.now(), LocalDateTime.now());
        when(activities.findIdsAfter(0, 1)).thenReturn(List.of(1L));
        when(redis.findExpiredRequestIds(eq(1L), anyLong(), eq(0L), eq(200))).thenReturn(List.of("request"));
        when(redis.findByRequestId(1, "request")).thenReturn(Optional.of(pending));
        when(durable.findByRequestId("request")).thenReturn(Optional.empty());
        when(durable.createPending(pending)).thenReturn(record);
        new RedisOrphanRecoveryJob(activities, redis, durable, compensation, issues).recover();
        var sequence = inOrder(durable, compensation);
        sequence.verify(durable).findByRequestId("request");
        sequence.verify(durable).createPending(pending);
        sequence.verify(compensation).compensateReservation(record);
        verifyNoInteractions(issues);
    }
}
