package com.flashguard.reservation;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.flashguard.order.OrderMapper;
import com.flashguard.order.OrderRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ReservationTimeoutCompensationService {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ReservationTimeoutCompensationService.class);

    private final StockReservationService stockReservationService;
    private final OrderMapper orderMapper;
    private final RedisReservationStore reservationStore;
    private final Clock clock;
    private final int scanBatchSize;
    private final Counter compensatedCounter;
    private final Counter noOrderCounter;
    private final Counter alreadyDoneCounter;
    private final Counter failedCounter;

    public ReservationTimeoutCompensationService(
        StockReservationService stockReservationService,
        OrderMapper orderMapper,
        RedisReservationStore reservationStore,
        MeterRegistry meterRegistry,
        @Value("${flashguard.compensation.scan-batch-size:200}") int scanBatchSize
    ) {
        this.stockReservationService = stockReservationService;
        this.orderMapper = orderMapper;
        this.reservationStore = reservationStore;
        this.clock = Clock.systemUTC();
        this.scanBatchSize = Math.max(1, scanBatchSize);
        this.compensatedCounter = Counter.builder("flashguard_reservation_compensated_total")
            .description("Number of timeout reservations successfully compensated")
            .register(meterRegistry);
        this.noOrderCounter = Counter.builder("flashguard_reservation_reconciled_total")
            .tag("outcome", "skipped")
            .description("Number of timeout reservations reconciled without compensation")
            .register(meterRegistry);
        this.alreadyDoneCounter = Counter.builder("flashguard_reservation_reconciled_total")
            .tag("outcome", "already_done")
            .description("Number of timeout reservations already transitioned")
            .register(meterRegistry);
        this.failedCounter = Counter.builder("flashguard_reservation_compensation_failed_total")
            .description("Number of timeout reservations failed to compensate")
            .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${flashguard.compensation.scan-interval:30s}")
    public void compensateExpiredReservations() {
        List<StockReservationRecord> expired = stockReservationService.findExpiredConsumable(
            LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
            scanBatchSize
        );
        for (StockReservationRecord reservation : expired) {
            compensateReservation(reservation);
        }
    }

    public void compensateReservation(StockReservationRecord reservation) {
        String messageKey = "AUTO_COMPENSATION_" + reservation.reservationId();
        String reservationId = reservation.reservationId();
        try {
            OrderRecord order = orderMapper.findByReservationId(reservationId).orElse(null);
            if (order != null) {
                int confirmed = stockReservationService.markAsConfirmed(reservationId, messageKey);
                if (confirmed == 1 || confirmed == 0) {
                    noOrderCounter.increment();
                    return;
                }
            }

            int cancelPending = stockReservationService.markAsCancelPending(reservationId, messageKey);
            if (cancelPending == 0) {
                alreadyDoneCounter.increment();
                return;
            }

            boolean released = reservationStore.releaseByRequestId(reservation.activityId(), reservation.requestId());
            if (released) {
                if (stockReservationService.markAsCancelled(reservationId, messageKey) == 1) {
                    compensatedCounter.increment();
                }
                return;
            }

            stockReservationService.markAsCompensationFailed(reservationId, messageKey);
            failedCounter.increment();
        } catch (Exception exception) {
            failedCounter.increment();
            LOGGER.warn("Compensation failed reservationId={} requestId={}; retry on next scan",
                reservationId, reservation.requestId(), exception);
            try {
                stockReservationService.markAsCompensationFailed(reservationId, messageKey);
            } catch (Exception recordingFailure) {
                // CANCEL_PENDING is scanned again even when the failure marker cannot be saved.
                LOGGER.warn("Cannot persist compensation failure reservationId={}", reservationId, recordingFailure);
            }
        }
    }
}
