package com.flashguard.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import com.flashguard.activity.ActivityQueryService;
import com.flashguard.activity.ActivityStatus;
import com.flashguard.activity.ActivityView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationWarmupServiceTest {

    @Mock
    private ActivityQueryService activityQueryService;

    @Mock
    private RedisReservationStore store;

    @Test
    void warmupPassesTheCurrentDatabaseSnapshotToRedis() {
        ActivityView snapshot = new ActivityView(
            1001L,
            "Flash sale",
            LocalDateTime.of(2026, 9, 6, 1, 0),
            LocalDateTime.of(2026, 9, 6, 2, 0),
            ActivityStatus.ACTIVE,
            100,
            73
        );
        when(activityQueryService.findById(1001L)).thenReturn(snapshot);
        when(store.warmUp(snapshot)).thenReturn(WarmUpResult.INITIALIZED);
        ReservationWarmupService service = new ReservationWarmupService(activityQueryService, store);

        assertThat(service.warmUp(1001L)).isEqualTo(WarmUpResult.INITIALIZED);
        verify(store).warmUp(snapshot);
    }
}
