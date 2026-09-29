package com.flashguard.mq;

import com.flashguard.order.ReservationConsumerService;
import com.flashguard.reservation.*;
import com.flashguard.shared.observability.CorrelationContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.LoggerFactory;
import java.util.List;

public class ReservationMessageConsumer implements MessageListenerConcurrently {
    private final ReservationMessageCodec codec;
    private final StockReservationService durable;
    private final ReservationConsumerService consumer;
    private final MeterRegistry meters;
    private final int retryDelayLevel;
    public ReservationMessageConsumer(ReservationMessageCodec codec, StockReservationService durable,
        ReservationConsumerService consumer, MeterRegistry meters, int retryDelayLevel) {
        this.codec = codec; this.durable = durable; this.consumer = consumer; this.meters = meters; this.retryDelayLevel = retryDelayLevel;
    }
    @Override public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        for (MessageExt message : messages) {
            PendingReservation pending = null;
            meters.counter("flashguard_mq_consume_attempts_total").increment();
            if (message.getReconsumeTimes() > 0) meters.counter("flashguard_mq_consume_retries_total").increment();
            try {
                pending = codec.decode(message);
                CorrelationContext.setTraceId(message.getUserProperty("traceId"));
                CorrelationContext.setRequestId(pending.requestId());
                CorrelationContext.setReservationId(pending.reservationId());
                CorrelationContext.setMessageKey(message.getKeys());
                StockReservationRecord row = durable.findByRequestId(pending.requestId()).orElseThrow();
                if (!ReservationMessageCodec.matches(pending, row)) throw new IllegalStateException("Message identity mismatch");
                if (!cancelled(row)) consumer.consumeReservation(pending.reservationId(), pending.reservationId());
            } catch (Exception failure) {
                // Compensation may have acquired ownership between the first read and the consumer's row lock.
                if (pending != null && cancellationWon(pending)) continue;
                meters.counter("flashguard_mq_consume_failures_total").increment();
                LoggerFactory.getLogger(getClass()).warn("Reservation consume failed msgId={} retry={}",message.getMsgId(),message.getReconsumeTimes(),failure);
                if (retryDelayLevel > 0) context.setDelayLevelWhenNextConsume(retryDelayLevel);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            } finally { CorrelationContext.clearAll(); }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
    private boolean cancellationWon(PendingReservation p) {
        try { return durable.findByRequestId(p.requestId()).filter(r -> ReservationMessageCodec.matches(p,r)).map(this::cancelled).orElse(false); }
        catch (Exception unavailable) { return false; }
    }
    private boolean cancelled(StockReservationRecord row) {
        return switch(row.status()) {case CANCEL_PENDING, CANCELLED, COMPENSATION_FAILED -> true; default -> false;};
    }
}
