package com.flashguard.reconciliation;

import java.util.List;

public record ReconciliationResult(
    long activityId,
    int initialStock,
    int availableStock,
    long dbReservationTotal,
    long dbReservationOpen,
    long dbReservationConfirmed,
    long dbOrderTotal,
    Long redisAvailableStock,
    long redisPendingCount,
    long dbConfirmedWithoutOrder,
    List<String> discrepancies,
    boolean balanced
) {}

