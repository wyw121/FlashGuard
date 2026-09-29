package com.flashguard.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Optional;

import com.flashguard.activity.ActivityMapper;
import com.flashguard.activity.ActivityStatus;
import com.flashguard.activity.ActivityView;
import com.flashguard.order.OrderMapper;
import com.flashguard.reservation.RedisReservationStore;
import com.flashguard.reservation.StockReservationService;
import org.junit.jupiter.api.Test;

class ReconciliationServiceTest {
    @Test
    void malformedRedisStockStillProducesAnAuditIssue() {
        ActivityMapper activities = mock(ActivityMapper.class);
        OrderMapper orders = mock(OrderMapper.class);
        StockReservationService reservations = mock(StockReservationService.class);
        RedisReservationStore redis = mock(RedisReservationStore.class);
        ReconciliationIssueMapper issues = mock(ReconciliationIssueMapper.class);
        when(activities.findById(1)).thenReturn(Optional.of(new ActivityView(1, "sale",
            LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1), ActivityStatus.ACTIVE, 10, 10)));
        when(redis.getAvailableStock(1)).thenThrow(new com.flashguard.reservation.ReservationStateException("not numeric"));
        ReconciliationResult result = new ReconciliationService(activities, orders, reservations, redis, issues)
            .reconcileActivity(1);
        assertThat(result.balanced()).isFalse();
        verify(issues).insert(eq(1L), contains("Redis snapshot unavailable"));
    }

    @Test
    void detectsCorruptRedisStockEvenWhenReservationCountsMatchAndPersistsEvidence() {
        ActivityMapper activities = mock(ActivityMapper.class);
        OrderMapper orders = mock(OrderMapper.class);
        StockReservationService reservations = mock(StockReservationService.class);
        RedisReservationStore redis = mock(RedisReservationStore.class);
        ReconciliationIssueMapper issues = mock(ReconciliationIssueMapper.class);
        when(activities.findById(1)).thenReturn(Optional.of(new ActivityView(1, "sale",
            LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1), ActivityStatus.ACTIVE, 10, 8)));
        when(orders.countByActivity(1)).thenReturn(2L);
        when(reservations.countOpenReservations(1)).thenReturn(1L);
        when(reservations.countConfirmedByActivity(1)).thenReturn(2L);
        // Redis retains successful reservation entries until activity archival.
        when(redis.countPendingReservations(1)).thenReturn(3L);
        when(redis.getAvailableStock(1)).thenReturn(99L);
        ReconciliationService service = new ReconciliationService(activities, orders, reservations, redis, issues);
        ReconciliationResult result = service.reconcileActivity(1);
        assertThat(result.balanced()).isFalse();
        assertThat(result.discrepancies()).anyMatch(item -> item.contains("Redis stock drift"));
        verify(issues).insert(eq(1L), contains("Redis stock drift"));
        verifyNoMoreInteractions(issues);
    }

    @Test
    void balancedStateDoesNotCreateAnIssueOrRepairStock() {
        ActivityMapper activities = mock(ActivityMapper.class);
        OrderMapper orders = mock(OrderMapper.class);
        StockReservationService reservations = mock(StockReservationService.class);
        RedisReservationStore redis = mock(RedisReservationStore.class);
        ReconciliationIssueMapper issues = mock(ReconciliationIssueMapper.class);
        when(activities.findById(1)).thenReturn(Optional.of(new ActivityView(1, "sale",
            LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1), ActivityStatus.ACTIVE, 10, 10)));
        when(redis.getAvailableStock(1)).thenReturn(10L);
        ReconciliationResult result = new ReconciliationService(activities, orders, reservations, redis, issues)
            .reconcileActivity(1);
        assertThat(result.balanced()).isTrue();
        verifyNoInteractions(issues);
        verify(redis, never()).releaseByRequestId(anyLong(), anyString());
    }
}
