package com.flashguard.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashguard.order.ReservationConsumerService;
import com.flashguard.reservation.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "flashguard.mq.enabled", havingValue = "true")
public class RocketMqConfiguration {
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public org.apache.rocketmq.tools.admin.DefaultMQAdminExt reservationMqAdmin(
        @Value("${flashguard.mq.nameserver:127.0.0.1:9876}") String nameserver) {
        var admin = new org.apache.rocketmq.tools.admin.DefaultMQAdminExt();
        admin.setNamesrvAddr(nameserver);
        admin.setInstanceName("flashguard-metrics-" + ProcessHandle.current().pid());
        admin.setVipChannelEnabled(false);
        return admin;
    }
    @Bean public RocketMqBacklogMetrics rocketMqBacklogMetrics(org.apache.rocketmq.tools.admin.DefaultMQAdminExt admin,
        @Value("${flashguard.mq.consumer-group:flashguard-reservation-consumer}") String group, MeterRegistry meters) {
        return new RocketMqBacklogMetrics(admin, group, meters);
    }
    @Bean public ReservationMessageCodec reservationMessageCodec(ObjectMapper mapper) { return new ReservationMessageCodec(mapper); }
    @Bean public ReservationLocalTransactionService reservationLocalTransactionService(StockReservationMapper mapper) { return new ReservationLocalTransactionService(mapper); }
    @Bean public ReservationTransactionListener reservationTransactionListener(ReservationMessageCodec codec,
        ReservationLocalTransactionService local, StockReservationService durable,
        ReservationTimeoutCompensationService compensation, MeterRegistry meters) {
        return new ReservationTransactionListener(codec,local,durable,compensation,meters);
    }
    @Bean(destroyMethod = "shutdown") public ExecutorService reservationTransactionCheckExecutor() {
        return new ThreadPoolExecutor(2,4,60,TimeUnit.SECONDS,new ArrayBlockingQueue<>(100),
            r -> {Thread t = new Thread(r,"flashguard-mq-check"); t.setDaemon(true); return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public TransactionMQProducer reservationTransactionProducer(ReservationTransactionListener listener,
        ExecutorService reservationTransactionCheckExecutor,
        @Value("${flashguard.mq.nameserver:127.0.0.1:9876}") String nameserver,
        @Value("${flashguard.mq.producer-group:flashguard-reservation-producer}") String group,
        @Value("${flashguard.mq.topic:flashguard-reservations}") String topic,
        @Value("${flashguard.mq.send-timeout-ms:5000}") int timeout) {
        TransactionMQProducer producer = new RecoverableTransactionProducer(group, topic);
        producer.setNamesrvAddr(nameserver);
        producer.setVipChannelEnabled(false);
        producer.setInstanceName(group + "-" + ProcessHandle.current().pid());
        producer.setSendMsgTimeout(timeout);
        producer.setTransactionListener(listener);
        producer.setExecutorService(reservationTransactionCheckExecutor);
        return producer;
    }
    @Bean public ReservationMessagePublisher reservationMessagePublisher(TransactionMQProducer producer,
        ReservationMessageCodec codec, StockReservationService durable, MeterRegistry meters,
        @Value("${flashguard.mq.topic:flashguard-reservations}") String topic) {
        return new RocketMqReservationPublisher(producer,codec,durable,topic,meters);
    }
    @Bean public ReservationMessageConsumer reservationMessageConsumer(ReservationMessageCodec codec,
        StockReservationService durable, ReservationConsumerService consumer, MeterRegistry meters,
        @Value("${flashguard.mq.retry-delay-level:0}") int retryDelayLevel) {
        return new ReservationMessageConsumer(codec,durable,consumer,meters,retryDelayLevel);
    }
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "flashguard.mq.consumer-enabled", havingValue = "true", matchIfMissing = true)
    public DefaultMQPushConsumer reservationPushConsumer(ReservationMessageConsumer listener,
        @Value("${flashguard.mq.nameserver:127.0.0.1:9876}") String nameserver,
        @Value("${flashguard.mq.consumer-group:flashguard-reservation-consumer}") String group,
        @Value("${flashguard.mq.topic:flashguard-reservations}") String topic,
        @Value("${flashguard.mq.max-reconsume-times:3}") int retries,
        @Value("${flashguard.mq.consume-thread-min:4}") int min,
        @Value("${flashguard.mq.consume-thread-max:8}") int max) throws Exception {
        if (retries < 0 || min < 1 || max < min) throw new IllegalArgumentException("Invalid MQ consumer bounds");
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(group);
        consumer.setNamesrvAddr(nameserver);
        consumer.setVipChannelEnabled(false);
        consumer.setInstanceName(group + "-" + ProcessHandle.current().pid());
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setConsumeThreadMin(min); consumer.setConsumeThreadMax(max);
        consumer.setPullThresholdForQueue(100);
        consumer.setMaxReconsumeTimes(retries);
        consumer.setAwaitTerminationMillisWhenShutdown(5000);
        consumer.subscribe(topic,"*");
        consumer.registerMessageListener(listener);
        return consumer;
    }
}


