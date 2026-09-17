package com.flashguard.mq;

import com.flashguard.reservation.*;
import com.flashguard.shared.observability.CorrelationContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.message.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.util.Map;

public class ReservationTransactionListener implements TransactionListener {
    private final ReservationMessageCodec codec;
    private final ReservationLocalTransactionService local;
    private final StockReservationService durable;
    private final ReservationTimeoutCompensationService compensation;
    private final MeterRegistry meters;
    public ReservationTransactionListener(ReservationMessageCodec codec, ReservationLocalTransactionService local,
        StockReservationService durable, ReservationTimeoutCompensationService compensation, MeterRegistry meters) {
        this.codec = codec; this.local = local; this.durable = durable; this.compensation = compensation; this.meters = meters;
    }
    @Override public LocalTransactionState executeLocalTransaction(Message message, Object ignored) {
        return decide(message, false);
    }
    @Override public LocalTransactionState checkLocalTransaction(MessageExt message) {
        return decide(message, true);
    }
    private LocalTransactionState decide(Message message, boolean check) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        LocalTransactionState result = LocalTransactionState.UNKNOW;
        try {
            PendingReservation p = codec.decode(message);
            CorrelationContext.setTraceId(message.getUserProperty("traceId"));
            CorrelationContext.setRequestId(p.requestId());
            CorrelationContext.setReservationId(p.reservationId());
            CorrelationContext.setMessageKey(message.getKeys());
            StockReservationRecord row;
            if (check) {
                row = durable.findCancellationFence(p.reservationId()).orElse(null);
                if (row == null) row = durable.findByRequestId(p.requestId()).orElse(null);
                if (row == null && p.expireAt() <= System.currentTimeMillis()) row = retryRolledBackLock(() -> local.fenceMissing(p));
            } else {
                row = retryRolledBackLock(() -> local.commit(p));
            }
            if (row != null) {
                if (!ReservationMessageCodec.matches(p, row)) result = LocalTransactionState.ROLLBACK_MESSAGE;
                else result = switch (row.status()) {
                    case PENDING, MQ_COMMITTED, CONFIRMED -> LocalTransactionState.COMMIT_MESSAGE;
                    case CANCEL_PENDING, COMPENSATION_FAILED, CANCELLED -> {
                        // Fence committed by a separate proxied DB transaction before any Redis release.
                        compensation.compensateReservation(row);
                        yield LocalTransactionState.ROLLBACK_MESSAGE;
                    }
                };
            }
        } catch (Exception failure) {
            // Commit response loss and database unavailability are ambiguous; never release Redis here.
            LoggerFactory.getLogger(getClass()).warn("Reservation transaction remains UNKNOWN; broker/scanner will retry", failure);
        } finally {
            if (check) meters.counter("flashguard_mq_transaction_checks_total", "state", result.name()).increment();
            MDC.clear();
            if (previous != null) MDC.setContextMap(previous);
        }
        return result;
    }

    private StockReservationRecord retryRolledBackLock(java.util.function.Supplier<StockReservationRecord> transaction) {
        for (int attempt = 1; ; attempt++) {
            try { return transaction.get(); }
            catch (org.springframework.dao.PessimisticLockingFailureException rolledBack) {
                // Every call crosses the Spring proxy: rollback completes before the next attempt.
                // Never retry a generic/ambiguous commit failure or send another half message here.
                if (attempt == 3) throw rolledBack;
                try { Thread.sleep(25L * attempt); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while retrying local transaction", interrupted);
                }
            }
        }
    }
}
