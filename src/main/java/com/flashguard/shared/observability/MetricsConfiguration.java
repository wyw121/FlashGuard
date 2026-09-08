package com.flashguard.shared.observability;

import com.flashguard.reservation.StockReservationService;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Component;

@Component
public class MetricsConfiguration {

    private final MeterRegistry meterRegistry;
    private final StockReservationService reservationService;

    public MetricsConfiguration(MeterRegistry meterRegistry, StockReservationService reservationService) {
        this.meterRegistry = meterRegistry;
        this.reservationService = reservationService;
    }

    @PostConstruct
    public void registerCustomMetrics() {
        Gauge.builder("flashguard_mq_backlog", reservationService, StockReservationService::countMqBacklog)
            .description("Number of reservations waiting for async consumer confirmation")
            .register(meterRegistry);
    }
}
