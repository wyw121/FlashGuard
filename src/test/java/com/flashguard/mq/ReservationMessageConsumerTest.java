package com.flashguard.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashguard.order.ReservationConsumerService;
import com.flashguard.reservation.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.common.message.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReservationMessageConsumerTest {
    final ReservationMessageCodec codec = new ReservationMessageCodec(new ObjectMapper());
    final StockReservationService durable = mock(StockReservationService.class);
    final ReservationConsumerService orders = mock(ReservationConsumerService.class);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final ReservationMessageConsumer adapter = new ReservationMessageConsumer(codec,durable,orders,meters,1);
    final PendingReservation p = new PendingReservation("res","1","2","req",ReservationStatus.PENDING,0,60000);
    @Test void cancelledMessageIsAcknowledgedWithoutOrderCreation() {
        when(durable.findByRequestId("req")).thenReturn(Optional.of(row(StockReservationStatus.CANCELLED)));
        assertThat(adapter.consumeMessage(List.of(message()),context())).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verifyNoInteractions(orders);
    }
    @Test void transientFailureRequestsBrokerRetryAndCleansCorrelation() {
        when(durable.findByRequestId("req")).thenReturn(Optional.of(row(StockReservationStatus.MQ_COMMITTED)));
        when(orders.consumeReservation("res","res")).thenThrow(new IllegalStateException("temporary DB error"));
        var context = context();
        assertThat(adapter.consumeMessage(List.of(message()),context)).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        assertThat(context.getDelayLevelWhenNextConsume()).isEqualTo(1);
        assertThat(meters.counter("flashguard_mq_consume_failures_total").count()).isEqualTo(1);
        assertThat(org.slf4j.MDC.get("requestId")).isNull();
    }
    private StockReservationRecord row(StockReservationStatus status) {return new StockReservationRecord(1,"res",1,2,"req",status,"res",LocalDateTime.ofInstant(Instant.ofEpochMilli(60000),ZoneOffset.UTC),null,null);}
    private MessageExt message() {var m = new MessageExt();m.setBody(codec.encode("t",p).getBody());m.setKeys("res");return m;}
    private ConsumeConcurrentlyContext context() {return new ConsumeConcurrentlyContext(new MessageQueue("t","b",0));}
}
