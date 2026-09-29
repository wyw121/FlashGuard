package com.flashguard.reconciliation;

import com.flashguard.activity.ActivityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReconciliationJob.class);
    private final ActivityMapper activities;
    private final ReconciliationService reconciliation;
    private long cursor;

    public ReconciliationJob(ActivityMapper activities, ReconciliationService reconciliation) {
        this.activities = activities;
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${flashguard.reconciliation.scan-interval:60s}")
    public synchronized void reconcile() {
        var ids = activities.findIdsAfter(cursor, 100);
        for (long activityId : ids) {
            try {
                reconciliation.reconcileActivity(activityId);
            } catch (Exception failure) {
                LOGGER.warn("Reconciliation failed activityId={}", activityId, failure);
            }
            cursor = activityId;
        }
        if (ids.size() < 100) cursor = 0;
    }
}
