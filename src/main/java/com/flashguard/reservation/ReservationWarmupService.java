package com.flashguard.reservation;

import com.flashguard.activity.ActivityMapper;
import org.springframework.stereotype.Service;

@Service
public class ReservationWarmupService {

    private final ActivityMapper activityMapper;
    private final RedisReservationStore reservationStore;

    public ReservationWarmupService(
        ActivityMapper activityMapper,
        RedisReservationStore reservationStore
    ) {
        this.activityMapper = activityMapper;
        this.reservationStore = reservationStore;
    }

    public WarmUpResult warmUp(long activityId) {
        return reservationStore.warmUp(activityMapper.findById(activityId)
            .orElseThrow(() -> new com.flashguard.shared.error.BusinessException(
                com.flashguard.shared.error.BusinessCode.ACTIVITY_NOT_FOUND)));
    }
}
