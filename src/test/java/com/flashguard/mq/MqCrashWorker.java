package com.flashguard.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashguard.FlashGuardApplication;
import com.flashguard.reservation.PendingReservation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Forked JVM fault injector: halt skips shutdown hooks, client cleanup and local transaction callbacks. */
public final class MqCrashWorker {
    private MqCrashWorker() {}
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path checkpoint = Path.of(args[1]);
        Path reservationFile = Path.of(args[2]);
        var context = new SpringApplicationBuilder(FlashGuardApplication.class)
            .web(WebApplicationType.NONE).run(Arrays.copyOfRange(args, 3, args.length));
        String topic = context.getEnvironment().getRequiredProperty("flashguard.mq.topic");
        if (mode.equals("consumer-before-commit")) {
            DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(context.getEnvironment().getRequiredProperty("flashguard.mq.consumer-group"));
            consumer.setNamesrvAddr(context.getEnvironment().getRequiredProperty("flashguard.mq.nameserver"));
            consumer.setVipChannelEnabled(false);
            consumer.setInstanceName("crash-worker-" + ProcessHandle.current().pid());
            consumer.setConsumeThreadMin(1); consumer.setConsumeThreadMax(1);
            consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            consumer.subscribe(topic, "*");
            consumer.registerMessageListener((MessageListenerConcurrently) (messages, consumeContext) -> {
                haltAt(checkpoint, "consumer-before-commit:" + messages.get(0).getKeys());
                throw new AssertionError("Runtime.halt returned");
            });
            consumer.start();
            Files.writeString(Path.of(checkpoint + ".ready"), "READY");
            Thread.sleep(120000);
            throw new IllegalStateException("No broker delivery before worker deadline");
        }
        ReservationTransactionListener actual = context.getBean(ReservationTransactionListener.class);
        TransactionMQProducer producer = context.getBean(TransactionMQProducer.class);
        producer.setTransactionListener(new TransactionListener() {
            @Override public LocalTransactionState executeLocalTransaction(Message message, Object arg) {
                if (mode.equals("producer-after-commit")) {
                    if (actual.executeLocalTransaction(message, arg) != LocalTransactionState.COMMIT_MESSAGE)
                        throw new IllegalStateException("Local transaction did not commit before crash");
                } else if (!mode.equals("producer-before-local")) {
                    throw new IllegalArgumentException("Unknown crash mode: " + mode);
                }
                haltAt(checkpoint, mode + ":" + message.getKeys());
                throw new AssertionError("Runtime.halt returned");
            }
            @Override public LocalTransactionState checkLocalTransaction(MessageExt message) { return actual.checkLocalTransaction(message); }
        });
        PendingReservation pending = context.getBean(ObjectMapper.class).readValue(Files.readString(reservationFile), PendingReservation.class);
        producer.sendMessageInTransaction(context.getBean(ReservationMessageCodec.class).encode(topic, pending), pending);
        throw new IllegalStateException("Crash callback was not invoked");
    }
    private static void haltAt(Path checkpoint, String content) {
        try { Files.writeString(checkpoint, content); }
        catch (Exception failure) { throw new IllegalStateException("Cannot persist crash checkpoint", failure); }
        Runtime.getRuntime().halt(137);
    }
}
