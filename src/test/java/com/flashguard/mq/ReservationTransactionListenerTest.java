package com.flashguard.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashguard.reservation.*;
import com.flashguard.shared.observability.CorrelationContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.apache.rocketmq.client.producer.LocalTransactionState.*;

class ReservationTransactionListenerTest {
    final ReservationMessageCodec codec = new ReservationMessageCodec(new ObjectMapper());
    final StockReservationService durable = mock(StockReservationService.class);
    final ReservationLocalTransactionService local = mock(ReservationLocalTransactionService.class);
    final ReservationTimeoutCompensationService compensation = mock(ReservationTimeoutCompensationService.class);
    final ReservationTransactionListener listener = new ReservationTransactionListener(codec, local, durable, compensation, new SimpleMeterRegistry());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void brokerCheckUsesMessageTraceAndRestoresPriorMdcEvenWhenDatabaseFails(boolean databaseFails) {
        Map<String, String> original = MDC.getCopyOfContextMap();
        Map<String, String> prior = Map.of(
            CorrelationContext.TRACE_ID, "worker-trace",
            CorrelationContext.REQUEST_ID, "worker-request",
            CorrelationContext.RESERVATION_ID, "worker-reservation",
            CorrelationContext.MESSAGE_KEY, "worker-key",
            "workerContext", "preserved");
        try {
            MDC.setContextMap(prior);
            var p = pending(System.currentTimeMillis() + 60000);
            var checkedMessage = message(p);
            checkedMessage.putUserProperty("traceId", "producer-trace");
            var duringLookup = new AtomicReference<Map<String, String>>();
            when(durable.findByRequestId(p.requestId())).thenAnswer(invocation -> {
                duringLookup.set(MDC.getCopyOfContextMap());
                if (databaseFails) throw new IllegalStateException("DB unavailable during check");
                return Optional.of(record(p, StockReservationStatus.MQ_COMMITTED));
            });

            assertThat(listener.checkLocalTransaction(checkedMessage))
                .isEqualTo(databaseFails ? UNKNOW : COMMIT_MESSAGE);
            assertThat(duringLookup.get()).containsEntry(CorrelationContext.TRACE_ID, "producer-trace")
                .containsEntry(CorrelationContext.REQUEST_ID, p.requestId())
                .containsEntry(CorrelationContext.RESERVATION_ID, p.reservationId())
                .containsEntry(CorrelationContext.MESSAGE_KEY, checkedMessage.getKeys());
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(prior);
        } finally {
            MDC.clear();
            if (original != null) MDC.setContextMap(original);
        }
    }

    @Test void missingUnexpiredRowIsUnknown() {
        PendingReservation p = pending(System.currentTimeMillis() + 60000);
        when(durable.findByRequestId(p.requestId())).thenReturn(Optional.empty());
        assertThat(listener.checkLocalTransaction(message(p))).isEqualTo(UNKNOW);
        verifyNoInteractions(local, compensation);
    }
    @Test void localDatabaseFailureLeavesRedisForRecovery() {
        PendingReservation p = pending(System.currentTimeMillis() + 60000);
        when(local.commit(p)).thenThrow(new IllegalStateException("ambiguous DB commit"));
        assertThat(listener.executeLocalTransaction(codec.encode("topic", p), p)).isEqualTo(UNKNOW);
        verify(local, times(1)).commit(p);
        verifyNoInteractions(compensation);
    }
    @Test void databaseFailureNeverRollsBack() {
        when(durable.findByRequestId("request")).thenThrow(new IllegalStateException("DB down"));
        assertThat(listener.checkLocalTransaction(message(pending(1)))).isEqualTo(UNKNOW);
        verifyNoInteractions(compensation);
    }
    @Test void cancellationFencePreventsLateLocalCommit() {
        PendingReservation p = pending(1);
        StockReservationRecord cancelled = record(p, StockReservationStatus.CANCEL_PENDING);
        when(local.commit(p)).thenReturn(cancelled);
        assertThat(listener.executeLocalTransaction(codec.encode("topic", p), p)).isEqualTo(ROLLBACK_MESSAGE);
        verify(compensation).compensateReservation(cancelled);
    }
    @Test void expiredMissingRowMustPersistFenceBeforeRollback() {
        PendingReservation p = pending(1);
        when(durable.findByRequestId("request")).thenReturn(Optional.empty());
        StockReservationRecord fence = record(p, StockReservationStatus.CANCEL_PENDING);
        when(local.fenceMissing(p)).thenReturn(fence);
        assertThat(listener.checkLocalTransaction(message(p))).isEqualTo(ROLLBACK_MESSAGE);
        var ordered = inOrder(local, compensation);
        ordered.verify(local).fenceMissing(p);
        ordered.verify(compensation).compensateReservation(fence);
    }
    @Test void everyDurableStateHasExplicitDecision() {
        PendingReservation p = pending(System.currentTimeMillis() + 60000);
        for (StockReservationStatus status : StockReservationStatus.values()) {
            when(durable.findByRequestId("request")).thenReturn(Optional.of(record(p, status)));
            assertThat(listener.checkLocalTransaction(message(p))).isEqualTo(
                switch(status) {case PENDING, MQ_COMMITTED, CONFIRMED -> COMMIT_MESSAGE; default -> ROLLBACK_MESSAGE;});
        }
    }
    @Test void identityMismatchNeverCommitsOrCompensatesOtherReservation() {
        PendingReservation p = pending(System.currentTimeMillis() + 60000);
        var wrong = new StockReservationRecord(1, "other", 1, 2, "request", StockReservationStatus.CONFIRMED, "other", null, null, null);
        when(durable.findByRequestId("request")).thenReturn(Optional.of(wrong));
        assertThat(listener.checkLocalTransaction(message(p))).isEqualTo(ROLLBACK_MESSAGE);
        verifyNoInteractions(compensation);
    }
    @Test void transientLocalLockFailureRetriesOnlyLocalTransactionThenCommits() {
        var p = pending(System.currentTimeMillis()+60000);
        var committed = record(p,StockReservationStatus.MQ_COMMITTED);
        when(local.commit(p)).thenThrow(new org.springframework.dao.PessimisticLockingFailureException("deadlock victim"))
            .thenReturn(committed);
        var halfMessage = codec.encode("topic",p);
        assertThat(listener.executeLocalTransaction(halfMessage,p)).isEqualTo(COMMIT_MESSAGE);
        verify(local,times(2)).commit(p);
        verifyNoMoreInteractions(local);
        verifyNoInteractions(durable,compensation);
    }

    @Test void exhaustedLocalLockRetriesRemainUnknownWithoutCompensation() {
        var p = pending(System.currentTimeMillis()+60000);
        when(local.commit(p)).thenThrow(new org.springframework.dao.PessimisticLockingFailureException("deadlock victim"));
        assertThat(listener.executeLocalTransaction(codec.encode("topic",p),p)).isEqualTo(UNKNOW);
        verify(local,times(3)).commit(p);
        verifyNoMoreInteractions(local);
        verifyNoInteractions(durable,compensation);
    }

    @Test void transientMissingFenceLockFailureRetriesBeforeAnyRelease() {
        var p = pending(1);
        var fence = record(p,StockReservationStatus.CANCEL_PENDING);
        when(durable.findByRequestId(p.requestId())).thenReturn(Optional.empty());
        when(local.fenceMissing(p)).thenThrow(new org.springframework.dao.PessimisticLockingFailureException("fence deadlock victim"))
            .thenReturn(fence);
        assertThat(listener.checkLocalTransaction(message(p))).isEqualTo(ROLLBACK_MESSAGE);
        var ordered = inOrder(local,compensation);
        ordered.verify(local,times(2)).fenceMissing(p);
        ordered.verify(compensation).compensateReservation(fence);
        verifyNoMoreInteractions(local,compensation);
        verify(durable,times(1)).findByRequestId(p.requestId());
    }

    @Test void exhaustedMissingFenceLockRetriesRemainUnknownWithoutRelease() {
        var p = pending(1);
        when(durable.findByRequestId(p.requestId())).thenReturn(Optional.empty());
        when(local.fenceMissing(p)).thenThrow(new org.springframework.dao.PessimisticLockingFailureException("fence deadlock victim"));
        assertThat(listener.checkLocalTransaction(message(p))).isEqualTo(UNKNOW);
        verify(local,times(3)).fenceMissing(p);
        verifyNoMoreInteractions(local);
        verifyNoInteractions(compensation);
    }

    @Test void ambiguousMissingFenceFailureIsNotAutomaticallyRetried() {
        var p = pending(1);
        when(durable.findByRequestId(p.requestId())).thenReturn(Optional.empty());
        when(local.fenceMissing(p)).thenThrow(new IllegalStateException("ambiguous commit response"));
        assertThat(listener.checkLocalTransaction(message(p))).isEqualTo(UNKNOW);
        verify(local,times(1)).fenceMissing(p);
        verifyNoMoreInteractions(local);
        verifyNoInteractions(compensation);
    }

    private PendingReservation pending(long expiry) { return new PendingReservation("reservation", "1", "2", "request", ReservationStatus.PENDING, 0, expiry); }
    private StockReservationRecord record(PendingReservation p, StockReservationStatus status) {
        return new StockReservationRecord(1,p.reservationId(),1,2,p.requestId(),status,p.reservationId(),java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(p.expireAt()),java.time.ZoneOffset.UTC),null,null);
    }
    private MessageExt message(PendingReservation p) {
        var m = new MessageExt(); m.setBody(codec.encode("topic",p).getBody()); m.setKeys(p.reservationId()); return m;
    }
}
