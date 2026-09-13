package com.flashguard.reservation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;

import com.flashguard.order.OrderMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ReservationTimeoutCompensationServiceTest {
    @Test
    void databaseFailureDuringFailureRecordingDoesNotAbortTheBatch() {
        StockReservationService durable = mock(StockReservationService.class);
        OrderMapper orders = mock(OrderMapper.class);
        RedisReservationStore redis = mock(RedisReservationStore.class);
        when(durable.findExpiredConsumable(any(), anyInt())).thenReturn(List.of(record("first"), record("second")));
        when(durable.markAsCancelPending(anyString(), anyString())).thenReturn(1);
        when(redis.releaseByRequestId(1, "first")).thenThrow(new ReservationStoreException("unavailable"));
        when(durable.markAsCompensationFailed(eq("first"), anyString())).thenThrow(new IllegalStateException("db unavailable"));
        when(redis.releaseByRequestId(1, "second")).thenReturn(true);
        when(durable.markAsCancelled(eq("second"), anyString())).thenReturn(1);
        ReservationTimeoutCompensationService service = new ReservationTimeoutCompensationService(
            durable, orders, redis, new SimpleMeterRegistry(), 200);
        assertThatCode(service::compensateExpiredReservations).doesNotThrowAnyException();
        verify(durable).markAsCancelled(eq("second"), anyString());
    }

    private StockReservationRecord record(String id) {
        LocalDateTime now = LocalDateTime.now();
        return new StockReservationRecord(1, id, 1, 1, id, StockReservationStatus.PENDING, null,
            now.minusMinutes(1), now, now);
    }
}
