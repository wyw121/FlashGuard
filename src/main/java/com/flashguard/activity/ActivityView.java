package com.flashguard.activity;

import java.time.LocalDateTime;

public record ActivityView(
    long id,
    String name,
    LocalDateTime startTime,
    LocalDateTime endTime,
    ActivityStatus status,
    int initialStock,
    int availableStock
) {
}
