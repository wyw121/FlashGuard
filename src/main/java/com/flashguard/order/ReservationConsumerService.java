package com.flashguard.order;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import com.flashguard.inventory.ActivityStockMapper;
import com.flashguard.reservation.StockReservationRecord;
import com.flashguard.reservation.StockReservationService;
import com.flashguard.reservation.StockReservationStatus;
import com.flashguard.shared.id.OrderNumberGenerator;
import com.flashguard.shared.observability.CorrelationContext;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class ReservationConsumerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReservationConsumerService.class);

    private final StockReservationService reservationService;
    private final ActivityStockMapper stockMapper;
    private final OrderMapper orderMapper;
    private final OrderNumberGenerator orderNumberGenerator;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final AtomicBoolean forceConsumerFailure;

    @Autowired
    public ReservationConsumerService(
        StockReservationService reservationService,
        ActivityStockMapper stockMapper,
        OrderMapper orderMapper,
        OrderNumberGenerator orderNumberGenerator,
        MeterRegistry meterRegistry
    ) {
        this(reservationService, stockMapper, orderMapper, orderNumberGenerator, meterRegistry, Clock.systemUTC());
    }

    ReservationConsumerService(
        StockReservationService reservationService,
        ActivityStockMapper stockMapper,
        OrderMapper orderMapper,
        OrderNumberGenerator orderNumberGenerator,
        MeterRegistry meterRegistry,
        Clock clock
    ) {
        this.reservationService = Objects.requireNonNull(reservationService);
        this.stockMapper = Objects.requireNonNull(stockMapper);
        this.orderMapper = Objects.requireNonNull(orderMapper);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        this.orderNumberGenerator = Objects.requireNonNull(orderNumberGenerator);
        this.clock = Objects.requireNonNull(clock);
        this.forceConsumerFailure = new AtomicBoolean(false);
    }

    @Transactional
    public OrderRecord consumeReservation(String reservationId, String messageKey) {
        if (reservationId == null || reservationId.isBlank()) {
            throw new IllegalArgumentException("reservationId cannot be blank");
        }

        long startNanos = System.nanoTime();
        CorrelationContext.setReservationId(reservationId);
        CorrelationContext.setMessageKey(messageKey);
        LOGGER.info("Consume reservation start reservationId={} messageKey={}", reservationId, messageKey);

        try {
            if (forceConsumerFailure.get()) {
                throw new IllegalStateException("Consumer forced failure is enabled for stage-8 fault injection");
            }

            StockReservationRecord reservation = reservationService.findByReservationIdForUpdate(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("Reservation not found: " + reservationId));

            if (reservation.status() == StockReservationStatus.CONFIRMED) {
                OrderRecord order = orderMapper.findByReservationId(reservationId)
                    .orElseThrow(() -> new IllegalStateException(
                        "Confirmed reservation has no order: " + reservationId
                    ));
                recordConsumerLatency("success", startNanos);
                return order;
            }

            if (reservation.status() == StockReservationStatus.PENDING) {
                if (reservationService.markAsMqCommitted(reservationId, messageKey) != 1) {
                    throw new IllegalStateException("Reservation cannot be consumed: " + reservationId);
                }
            } else if (reservation.status() != StockReservationStatus.MQ_COMMITTED) {
                throw new IllegalStateException("Reservation status is not consumable: " + reservation.status());
            }

            OrderRecord result = orderMapper.findByReservationId(reservationId)
                .map(order -> {
                    reservationService.markAsConfirmed(reservationId, messageKey);
                    return order;
                })
                .orElseGet(() -> consumeByCreateOrder(reservation, messageKey));
            recordConsumerLatency("success", startNanos);
            return result;
        } catch (Exception exception) {
            recordConsumerLatency("failed", startNanos);
            throw exception;
        } finally {
            CorrelationContext.clearBusinessContext();
        }
    }

    public void setForcedFailureEnabled(boolean enabled) {
        forceConsumerFailure.set(enabled);
    }

    public boolean isForcedFailureEnabled() {
        return forceConsumerFailure.get();
    }

    private void recordConsumerLatency(String outcome, long startNanos) {
        Timer timer = Timer.builder("flashguard_mq_consumer_latency")
            .tag("outcome", outcome)
            .register(meterRegistry);
        timer.record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private OrderRecord consumeByCreateOrder(StockReservationRecord reservation, String messageKey) {
        if (stockMapper.decrementIfAvailable(reservation.activityId()) != 1) {
            throw new IllegalStateException("Stock is no longer available: " + reservation.activityId());
        }

        OrderRecord order = buildOrder(reservation);
        try {
            orderMapper.insertWithReservation(order, reservation.reservationId());
        } catch (DuplicateKeyException exception) {
            OrderRecord existingOrder = orderMapper.findByReservationId(reservation.reservationId())
                .orElseThrow(() -> exception);
            reservationService.markAsConfirmed(reservation.reservationId(), messageKey);
            return existingOrder;
        }

        reservationService.markAsConfirmed(reservation.reservationId(), messageKey);
        return order;
    }

    private OrderRecord buildOrder(StockReservationRecord reservation) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return new OrderRecord(
            0L,
            orderNumberGenerator.next(),
            reservation.requestId(),
            reservation.activityId(),
            reservation.userId(),
            OrderStatus.CONFIRMED,
            now,
            now
        );
    }
}
