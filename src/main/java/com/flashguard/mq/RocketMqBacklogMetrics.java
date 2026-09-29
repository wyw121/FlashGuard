package com.flashguard.mq;

import java.util.concurrent.atomic.AtomicLong;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.springframework.scheduling.annotation.Scheduled;

/** Broker offsets are sampled in the background; -1 means unavailable, never a false zero. */
public class RocketMqBacklogMetrics {
    private final DefaultMQAdminExt admin;
    private final String group;
    private final MeterRegistry meters;
    private final AtomicLong backlog = new AtomicLong(-1);
    private final AtomicLong deadLetters = new AtomicLong(-1);

    public RocketMqBacklogMetrics(DefaultMQAdminExt admin, String group, MeterRegistry meters) {
        this.admin = admin; this.group = group; this.meters = meters;
        Gauge.builder("flashguard_mq_backlog", backlog, AtomicLong::doubleValue)
            .description("Broker offset minus consumer offset; -1 when unavailable").register(meters);
        Gauge.builder("flashguard_mq_dlq_messages", deadLetters, AtomicLong::doubleValue)
            .description("Retained messages in the consumer group's dead-letter topic; -1 when unavailable").register(meters);
    }

    @Scheduled(fixedDelayString = "${flashguard.mq.metrics-interval:5s}")
    public void refresh() {
        try {
            backlog.set(Math.max(0, admin.examineConsumeStats(group).computeTotalDiff()));
        } catch (Exception failure) {
            backlog.set(-1);
            meters.counter("flashguard_mq_metrics_errors_total", "source", "backlog").increment();
        }
        try {
            deadLetters.set(admin.examineTopicStats("%DLQ%" + group).getOffsetTable().values().stream()
                .mapToLong(offset -> Math.max(0, offset.getMaxOffset() - offset.getMinOffset())).sum());
        } catch (MQClientException failure) {
            deadLetters.set(failure.getResponseCode() == 17 ? 0 : -1);
        } catch (Exception failure) {
            deadLetters.set(-1);
        }
    }
}
