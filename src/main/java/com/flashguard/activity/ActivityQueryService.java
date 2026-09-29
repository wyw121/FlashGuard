package com.flashguard.activity;

import org.springframework.stereotype.Service;

@Service
public class ActivityQueryService {

    private final ActivityCacheService activityCacheService;

    public ActivityQueryService(ActivityCacheService activityCacheService) {
        this.activityCacheService = activityCacheService;
    }

    public ActivityView findById(long activityId) {
        return activityCacheService.findById(activityId);
    }
}
