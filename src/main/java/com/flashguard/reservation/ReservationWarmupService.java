package com.flashguard.reservation;

import com.flashguard.activity.ActivityQueryService;
import org.springframework.stereotype.Service;

@Service
public class ReservationWarmupService {

    private final ActivityQueryService activityQueryService;
    private final RedisReservationStore reservationStore;

    public ReservationWarmupService(
        ActivityQueryService activityQueryService,
        RedisReservationStore reservationStore
    ) {
        this.activityQueryService = activityQueryService;
        this.reservationStore = reservationStore;
    }

    public WarmUpResult warmUp(long activityId) {
        return reservationStore.warmUp(activityQueryService.findById(activityId));
    }
}
