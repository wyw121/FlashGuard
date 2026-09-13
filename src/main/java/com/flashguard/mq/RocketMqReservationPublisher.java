package com.flashguard.mq;

import com.flashguard.reservation.*;
import com.flashguard.shared.error.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.producer.*;
import org.slf4j.LoggerFactory;

public class RocketMqReservationPublisher implements ReservationMessagePublisher {
    private final TransactionMQProducer producer;
    private final ReservationMessageCodec codec;
    private final StockReservationService durable;
    private final String topic;
    private final MeterRegistry meters;
    public RocketMqReservationPublisher(TransactionMQProducer producer, ReservationMessageCodec codec,
        StockReservationService durable, String topic, MeterRegistry meters) {
        this.producer = producer; this.codec = codec; this.durable = durable; this.topic = topic; this.meters = meters;
    }
    @Override public StockReservationRecord publish(PendingReservation pending) {
        try {
            // Even replay sends a new half message: a durable row alone cannot prove broker delivery.
            TransactionSendResult sent = producer.sendMessageInTransaction(codec.encode(topic, pending), pending);
            if (sent.getSendStatus() != SendStatus.SEND_OK || sent.getLocalTransactionState() != LocalTransactionState.COMMIT_MESSAGE)
                throw new IllegalStateException("Broker transaction has not committed");
            StockReservationRecord row = durable.findByRequestId(pending.requestId()).orElseThrow();
            if (!ReservationMessageCodec.matches(pending, row)) throw new IllegalStateException("Reservation identity conflict");
            meters.counter("flashguard_mq_publish_total", "outcome", "committed").increment();
            return row;
        } catch (Exception failure) {
            meters.counter("flashguard_mq_publish_total", "outcome", "unknown_or_failed").increment();
            LoggerFactory.getLogger(getClass()).warn("Publish failed or ambiguous reservationId={}; retained for recovery", pending.reservationId(), failure);
            // sendMessageInTransaction may throw after broker acceptance. Only durable cancellation may release stock.
            throw new BusinessException(BusinessCode.SERVICE_UNAVAILABLE);
        }
    }
}
