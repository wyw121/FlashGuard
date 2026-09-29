package com.flashguard.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.flashguard.inventory.ActivityStockMapper;
import com.flashguard.reservation.StockReservationRecord;
import com.flashguard.reservation.StockReservationService;
import com.flashguard.reservation.StockReservationStatus;
import com.flashguard.shared.id.OrderNumberGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class ReservationConsumerServiceTest {

    private static final Clock NOW = Clock.fixed(
        Instant.parse("2026-09-07T06:00:00Z"),
        ZoneOffset.UTC
    );

    @Mock
    private StockReservationService reservationService;

    @Mock
    private ActivityStockMapper stockMapper;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderNumberGenerator orderNumberGenerator;

    private ReservationConsumerService service;

    @BeforeEach
    void setUp() {
        service = new ReservationConsumerService(
            reservationService,
            stockMapper,
            orderMapper,
            orderNumberGenerator,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            NOW
        );
    }

    @Test
    void consumesPendingReservationToCreateConfirmedOrder() {
        StockReservationRecord reservation = pendingReservation();
        when(reservationService.findByReservationIdForUpdate("res-1001")).thenReturn(Optional.of(reservation));
        when(reservationService.markAsMqCommitted("res-1001", "msg-1")).thenReturn(1);
        when(orderMapper.findByReservationId("res-1001")).thenReturn(Optional.empty());
        when(stockMapper.decrementIfAvailable(1001L)).thenReturn(1);
        when(orderNumberGenerator.next()).thenReturn("01K4C1YJ0000000000000000OR");
        when(orderMapper.findByReservationId("res-1001")).thenReturn(Optional.empty());

        service.consumeReservation("res-1001", "msg-1");

        ArgumentCaptor<OrderRecord> order = ArgumentCaptor.forClass(OrderRecord.class);
        verify(orderMapper).insertWithReservation(order.capture(), org.mockito.ArgumentMatchers.eq("res-1001"));
        verify(reservationService).markAsMqCommitted("res-1001", "msg-1");
        verify(reservationService).markAsConfirmed("res-1001", "msg-1");
        assertThat(order.getValue().requestId()).isEqualTo("request-1");
        assertThat(order.getValue().activityId()).isEqualTo(1001L);
        assertThat(order.getValue().userId()).isEqualTo(2001L);
    }

    @Test
    void returnsExistingOrderForAlreadyConfirmedReservation() {
        StockReservationRecord reservation = new StockReservationRecord(
            1L,
            "res-1001",
            1001L,
            2001L,
            "request-1",
            StockReservationStatus.CONFIRMED,
            "mq-1",
            LocalDateTime.ofInstant(NOW.instant(), ZoneOffset.UTC),
            LocalDateTime.ofInstant(NOW.instant(), ZoneOffset.UTC),
            LocalDateTime.ofInstant(NOW.instant(), ZoneOffset.UTC)
        );
        when(reservationService.findByReservationIdForUpdate("res-1001")).thenReturn(Optional.of(reservation));
        when(orderMapper.findByReservationId("res-1001")).thenReturn(Optional.of(
            confirmedOrder("order-existing", "request-1", 1001L, 2001L)
        ));

        OrderRecord result = service.consumeReservation("res-1001", "msg-1");

        assertThat(result.orderNo()).isEqualTo("order-existing");
        verify(orderMapper, never()).insertWithReservation(
            org.mockito.ArgumentMatchers.<OrderRecord>any(),
            anyString()
        );
    }

    @Test
    void duplicateInsertFallsBackToExistingOrder() {
        StockReservationRecord reservation = pendingReservation();
        when(reservationService.findByReservationIdForUpdate("res-1001")).thenReturn(Optional.of(reservation));
        when(reservationService.markAsMqCommitted("res-1001", "msg-1")).thenReturn(1);
        when(orderNumberGenerator.next()).thenReturn("01K4C1YJ0000000000000000OD");
        doThrow(new DuplicateKeyException("duplicate"))
            .when(orderMapper)
            .insertWithReservation(any(OrderRecord.class), anyString());
        when(orderMapper.findByReservationId("res-1001")).thenReturn(Optional.empty(), Optional.of(
            confirmedOrder("order-existing", "request-1", 1001L, 2001L)
        ));

        OrderRecord result = service.consumeReservation("res-1001", "msg-1");

        assertThat(result.orderNo()).isEqualTo("order-existing");
        verify(stockMapper, never()).decrementIfAvailable(1001L);
        verify(reservationService).markAsConfirmed("res-1001", "msg-1");
    }

    @Test
    void throwsForMissingReservation() {
        when(reservationService.findByReservationIdForUpdate("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consumeReservation("missing", "msg-1"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private StockReservationRecord pendingReservation() {
        return new StockReservationRecord(
            1L,
            "res-1001",
            1001L,
            2001L,
            "request-1",
            StockReservationStatus.PENDING,
            "mq-1",
            LocalDateTime.ofInstant(NOW.instant(), ZoneOffset.UTC),
            LocalDateTime.ofInstant(NOW.instant(), ZoneOffset.UTC),
            LocalDateTime.ofInstant(NOW.instant(), ZoneOffset.UTC)
        );
    }

    private OrderRecord confirmedOrder(String orderNo, String requestId, long activityId, long userId) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW.instant(), ZoneOffset.UTC);
        return new OrderRecord(
            11L,
            orderNo,
            requestId,
            activityId,
            userId,
            OrderStatus.CONFIRMED,
            now,
            now
        );
    }
}
