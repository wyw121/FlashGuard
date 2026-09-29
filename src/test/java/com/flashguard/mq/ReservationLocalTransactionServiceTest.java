package com.flashguard.mq;

import com.flashguard.reservation.*;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReservationLocalTransactionServiceTest {
    final StockReservationMapper mapper = mock(StockReservationMapper.class);
    final ReservationLocalTransactionService service = new ReservationLocalTransactionService(mapper);
    @Test void conflictFenceStopsLateCallbackBeforeAnyStockMutation() {
        var p = pending();
        var cancelled = row(p,StockReservationStatus.CANCELLED);
        when(mapper.findCancellationFenceForUpdate("res")).thenReturn(Optional.of(cancelled));
        assertThat(service.commit(p)).isSameAs(cancelled);
        verify(mapper,never()).insert(any());
        verify(mapper,never()).markAsMqCommitted(anyString(),anyString());
        verify(mapper,never()).findByReservationIdForUpdate(anyString());
    }
    @Test void lateCallbackDoesNotReviveCancellation() {
        var p = pending();
        var row = row(p, StockReservationStatus.CANCEL_PENDING);
        when(mapper.findByReservationIdForUpdate("res")).thenReturn(Optional.of(row));
        assertThat(service.commit(p)).isSameAs(row);
        verify(mapper, never()).markAsMqCommitted(anyString(),anyString());
        verify(mapper, never()).insert(any());
    }
    @Test void missingExpiredRowIsInsertedAsCancellationFence() {
        var p = new PendingReservation("res","1","2","req",ReservationStatus.PENDING,0,1);
        when(mapper.findByReservationIdForUpdate("res")).thenReturn(Optional.empty());
        var fence = service.fenceMissing(p);
        assertThat(fence.status()).isEqualTo(StockReservationStatus.CANCEL_PENDING);
        verify(mapper).insert(fence);
    }
    private PendingReservation pending() {return new PendingReservation("res","1","2","req",ReservationStatus.PENDING,0,System.currentTimeMillis()+60000);}
    private StockReservationRecord row(PendingReservation p,StockReservationStatus status) {
        return new StockReservationRecord(1,"res",1,2,"req",status,null,java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(p.expireAt()),java.time.ZoneOffset.UTC),null,null);
    }
}
