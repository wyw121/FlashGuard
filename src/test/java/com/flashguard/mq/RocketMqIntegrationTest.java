package com.flashguard.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashguard.activity.ActivityMapper;
import com.flashguard.order.ReservationConsumerService;
import com.flashguard.reservation.*;
import com.flashguard.support.MySqlContainerSupport;
import com.flashguard.support.RocketMqProcessSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.flashguard.participation.*;
import com.flashguard.order.OrderMapper;
import com.flashguard.shared.error.BusinessException;
import com.flashguard.shared.error.BusinessCode;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.*;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Broker, MySQL and Redis are real; no direct listener invocation stands in for delivery. */
@SpringBootTest(properties = {"flashguard.mq.enabled=false", "flashguard.scheduling.enabled=false",
    "spring.datasource.hikari.connection-timeout=2000", "spring.datasource.hikari.validation-timeout=1000",
    "spring.datasource.hikari.data-source-properties.socketTimeout=1500",
    "spring.datasource.hikari.data-source-properties.connectTimeout=1500"})
@Import(RocketMqIntegrationTest.LocalConfiguration.class)
class RocketMqIntegrationTest extends MySqlContainerSupport {
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.8"))
        .withExposedPorts(6379);
    static RocketMqProcessSupport broker;
    static final AtomicInteger IDS = new AtomicInteger(6100);
    final List<DefaultMQPushConsumer> consumers = new ArrayList<>();
    final List<TransactionMQProducer> producers = new ArrayList<>();
    DefaultMQAdminExt admin;
    String topic;
    String group;
    long activity;
    @Autowired JdbcTemplate jdbc;
    @Autowired ActivityMapper activities;
    @Autowired StockReservationService durable;
    @Autowired StockReservationMapper reservations;
    @Autowired ReservationTimeoutCompensationService compensation;
    @Autowired RedisReservationStore store;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;
    @Autowired ReservationLocalTransactionService local;
    @Autowired ReservationConsumerService orderConsumer;
    @Autowired MeterRegistry meters;
    @Autowired ParticipationTransaction participationTransaction;
    @Autowired ParticipationRateLimiter rateLimiter;
    @Autowired OrderMapper orders;
    @Autowired RedisOrphanRecoveryJob orphanRecovery;
    ReservationMessageCodec codec;
    ReservationTransactionListener listener;

    @TestConfiguration
    static class LocalConfiguration {
        @Bean ReservationLocalTransactionService localTransaction(StockReservationMapper mapper) {
            return new ReservationLocalTransactionService(mapper);
        }
    }
    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        if (MYSQL == null) throw new IllegalStateException("MQ acceptance requires its own MySQL container; external DB is forbidden");
        REDIS.start();
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
    @BeforeAll static void startBroker() throws Exception { broker = new RocketMqProcessSupport(); broker.start(); }
    @AfterAll static void stopInfrastructure() { if (broker != null) broker.close(); REDIS.stop(); }
    @BeforeEach void setup() throws Exception {
        activity = IDS.incrementAndGet();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        topic = "fg_it_" + suffix;
        group = "fg_it_c_" + suffix;
        codec = new ReservationMessageCodec(json);
        listener = new ReservationTransactionListener(codec, local, durable, compensation, meters);
        admin = new DefaultMQAdminExt();
        admin.setNamesrvAddr(broker.nameserverAddress());
        admin.setInstanceName("admin_" + suffix);
        admin.setVipChannelEnabled(false);
        admin.start();
        admin.createAndUpdateTopicConfig(broker.brokerAddress(), new TopicConfig(topic, 1, 1, 6));
        await().atMost(Duration.ofSeconds(45)).ignoreExceptions().until(() ->
            !admin.examineTopicRouteInfo(topic).getQueueDatas().isEmpty());
        jdbc.update("INSERT INTO activity(id,name,start_time,end_time,status) VALUES (?,'real-mq',UTC_TIMESTAMP()-INTERVAL 1 HOUR,UTC_TIMESTAMP()+INTERVAL 1 HOUR,'ACTIVE')", activity);
        jdbc.update("INSERT INTO activity_stock(activity_id,initial_stock,available_stock) VALUES (?,200,200)", activity);
        store.warmUp(activities.findById(activity).orElseThrow());
    }
    @AfterEach void shutdownClients() {
        consumers.forEach(DefaultMQPushConsumer::shutdown);
        producers.forEach(TransactionMQProducer::shutdown);
        if (admin != null) admin.shutdown();
    }

    @Test void normalPublishReachesConfirmedThroughBroker() throws Exception {
        startConsumer(group, productionConsumer());
        PendingReservation pending = reserve(1, Duration.ofMinutes(5));
        publish(producer(listener), pending);
        confirmed(pending);
        assertSingleOrderAndDebit(pending);
    }

    @Test void oneHundredActualBrokerMessagesCreateOneOrderAndDebit() throws Exception {
        AtomicInteger deliveries = new AtomicInteger();
        ReservationMessageConsumer actual = productionConsumer();
        startConsumer(group, (messages, context) -> {
            ConsumeConcurrentlyStatus outcome = actual.consumeMessage(messages, context);
            if (outcome == ConsumeConcurrentlyStatus.CONSUME_SUCCESS) deliveries.addAndGet(messages.size());
            return outcome;
        });
        PendingReservation pending = reserve(1, Duration.ofMinutes(5));
        TransactionMQProducer producer = producer(listener);
        for (int i = 0; i < 100; i++) publish(producer, pending);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(deliveries.get()).isGreaterThanOrEqualTo(100));
        confirmed(pending);
        assertSingleOrderAndDebit(pending);
    }

    @Test void brokerCheckCommitsAfterLocalCommitReturnedUnknown() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        startConsumer(group, productionConsumer());
        PendingReservation pending = reserve(1, Duration.ofMinutes(5));
        TransactionMQProducer producer = producer(unknownListener(true, checks));
        assertThat(producer.sendMessageInTransaction(codec.encode(topic, pending), pending).getLocalTransactionState())
            .isEqualTo(LocalTransactionState.UNKNOW);
        confirmed(pending);
        assertThat(checks.get()).isPositive();
        assertSingleOrderAndDebit(pending);
    }

    @Test void brokerCheckFencesMissingExpiredTransactionAndReleasesRedis() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger deliveries = new AtomicInteger();
        startConsumer(group, (messages, context) -> { deliveries.addAndGet(messages.size()); return ConsumeConcurrentlyStatus.CONSUME_SUCCESS; });
        PendingReservation pending = reserve(1, Duration.ofSeconds(6));
        producer(unknownListener(false, checks)).sendMessageInTransaction(codec.encode(topic, pending), pending);
        assertThat(reservations.findByReservationId(pending.reservationId())).isEmpty();
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
            assertThat(reservations.findByReservationId(pending.reservationId()))
                .hasValueSatisfying(row -> assertThat(row.status()).isEqualTo(StockReservationStatus.CANCELLED)));
        assertThat(checks.get()).isPositive();
        assertThat(store.getAvailableStock(activity)).isEqualTo(200);
        assertThat(orderCount()).isZero();
        assertThat(deliveries.get()).isZero();
    }

    @Test void pausedOwnedMysqlCausesBrokerRetryThenRecovers() throws Exception {
        PendingReservation pending = reserve(1, Duration.ofMinutes(5));
        publish(producer(listener), pending);
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger redeliveries = new AtomicInteger();
        AtomicInteger failedRedeliveries = new AtomicInteger();
        AtomicInteger successfulRedeliveries = new AtomicInteger();
        ReservationMessageConsumer actual = productionConsumer();
        String container = MYSQL.getContainerId();
        DockerClientFactory.instance().client().pauseContainerCmd(container).exec();
        try {
            startConsumer(group, (messages, context) -> {
                messages.forEach(m -> { if (m.getReconsumeTimes() > 0) redeliveries.incrementAndGet(); });
                ConsumeConcurrentlyStatus result = actual.consumeMessage(messages, context);
                if (result == ConsumeConcurrentlyStatus.RECONSUME_LATER) failures.incrementAndGet();
                if (messages.stream().anyMatch(m -> m.getReconsumeTimes() > 0)) {
                    if (result == ConsumeConcurrentlyStatus.RECONSUME_LATER) failedRedeliveries.incrementAndGet();
                    else if (result == ConsumeConcurrentlyStatus.CONSUME_SUCCESS) successfulRedeliveries.incrementAndGet();
                }
                return result;
            }, 20);
            // Keep MySQL paused until the SDK has returned RECONSUME_LATER, the broker
            // has persisted/re-delivered a retry, and that retry also fails against real MySQL.
            await().atMost(Duration.ofSeconds(45)).until(() -> failures.get() > 0 && failedRedeliveries.get() > 0);
        } finally { DockerClientFactory.instance().client().unpauseContainerCmd(container).exec(); }
        await().atMost(Duration.ofSeconds(60)).until(() -> successfulRedeliveries.get() > 0);
        confirmed(pending);
        assertThat(redeliveries.get()).isPositive();
        System.out.println("FLASHGUARD_MYSQL_RETRY failures=" + failures.get() + " failedBrokerRetries=" + failedRedeliveries.get()
            + " successfulBrokerRetries=" + successfulRedeliveries.get());
        assertSingleOrderAndDebit(pending);
    }

    @Test void persistentConsumerFailureReachesRealDlqThenTimeoutCompensates() throws Exception {
        PendingReservation pending = reserve(1, Duration.ofSeconds(20));
        // Real inventory constraint failure rolls back each consumer transaction.
        jdbc.update("UPDATE activity_stock SET available_stock=0 WHERE activity_id=?", activity);
        startConsumer(group, productionConsumer(), 2);
        publish(producer(listener), pending);
        String dlq = "%DLQ%" + group;
        await().atMost(Duration.ofSeconds(60)).ignoreExceptions().until(() ->
            admin.examineTopicStats(dlq).getOffsetTable().values().stream().anyMatch(offset -> offset.getMaxOffset() > 0));
        assertThat(orderCount()).isZero();
        assertThat(reservations.findByReservationId(pending.reservationId()).orElseThrow().status()).isEqualTo(StockReservationStatus.MQ_COMMITTED);
        // Remove only the injected inventory fault before checking the recovered invariant.
        jdbc.update("UPDATE activity_stock SET available_stock=200 WHERE activity_id=?", activity);
        await().atMost(Duration.ofSeconds(40)).until(() -> System.currentTimeMillis() > pending.expireAt());
        compensation.compensateExpiredReservations();
        assertThat(reservations.findByReservationId(pending.reservationId()).orElseThrow().status()).isEqualTo(StockReservationStatus.CANCELLED);
        assertThat(store.getAvailableStock(activity)).isEqualTo(200);
        assertThat(activities.findById(activity).orElseThrow().availableStock()).isEqualTo(200);
        assertThat(orderCount()).isZero();
    }

    @Test void stoppedConsumerRestartsAndDrainsBrokerBacklog() throws Exception {
        DefaultMQPushConsumer first = startConsumer(group, productionConsumer());
        PendingReservation initial = reserve(1, Duration.ofMinutes(5));
        TransactionMQProducer producer = producer(listener);
        publish(producer, initial);
        confirmed(initial);
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() ->
            assertThat(admin.examineConsumeStats(group, topic).computeTotalDiff()).isZero());
        first.shutdown(); consumers.remove(first);
        long consumerOffsetBefore = admin.examineConsumeStats(group, topic).getOffsetTable().values().stream()
            .mapToLong(offset -> offset.getConsumerOffset()).sum();
        long brokerOffsetBefore = admin.examineTopicStats(topic).getOffsetTable().values().stream()
            .mapToLong(offset -> offset.getMaxOffset()).sum();
        List<PendingReservation> backlog = new ArrayList<>();
        for (int i = 2; i <= 21; i++) {
            PendingReservation pending = reserve(i, Duration.ofMinutes(5));
            backlog.add(pending); publish(producer, pending);
        }
        assertThat(orderCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_reservation WHERE activity_id=? AND status='MQ_COMMITTED'", Long.class, activity))
            .isEqualTo(20);
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
            long produced = admin.examineTopicStats(topic).getOffsetTable().values().stream().mapToLong(offset -> offset.getMaxOffset()).sum();
            var stats = admin.examineConsumeStats(group, topic);
            assertThat(produced - brokerOffsetBefore).isGreaterThanOrEqualTo(20);
            assertThat(stats.getOffsetTable().values().stream().mapToLong(offset -> offset.getConsumerOffset()).sum()).isEqualTo(consumerOffsetBefore);
            assertThat(stats.computeTotalDiff()).isEqualTo(produced - consumerOffsetBefore);
        });
        long pendingMessages = admin.examineConsumeStats(group, topic).computeTotalDiff();
        System.out.println("FLASHGUARD_BROKER_BACKLOG topic=" + topic + " group=" + group + " uniqueReservations=20 pendingMessages="
            + pendingMessages + " brokerOffsetBefore=" + brokerOffsetBefore + " consumerOffsetBefore=" + consumerOffsetBefore);
        startConsumer(group, productionConsumer());
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(orderCount()).isEqualTo(21));
        for (PendingReservation pending : backlog) confirmed(pending);
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() ->
            assertThat(admin.examineConsumeStats(group, topic).computeTotalDiff()).isZero());
        System.out.println("FLASHGUARD_BROKER_BACKLOG topic=" + topic + " group=" + group + " pending=0");
        assertThat(activities.findById(activity).orElseThrow().availableStock()).isEqualTo(179);
        assertThat(store.getAvailableStock(activity)).isEqualTo(179);
    }

    @Test void pausedOwnedRedisFailsClosedWithoutDurableOrder() throws Exception {
        TransactionMQProducer producer = producer(listener);
        RedisReservationStore shortLived = new RedisReservationStore(redis, Duration.ofSeconds(2));
        ParticipationService async = new ParticipationService(participationTransaction, orders, shortLived, durable,
            rateLimiter, new RocketMqReservationPublisher(producer, codec, durable, topic, meters), "async");
        String request = UUID.randomUUID().toString();
        DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                async.participate(new ParticipationCommand(activity, 1, request)))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.code()).isEqualTo(BusinessCode.SERVICE_UNAVAILABLE));
            assertThat(durable.findByRequestId(request)).isEmpty();
            assertThat(orderCount()).isZero();
        } finally { DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec(); }
        // A timed-out Redis command may execute after unpause. Read behind it on the same
        // shared Lettuce connection, then exercise the real orphan scanner if stock was reserved.
        assertThat(redis.execute((org.springframework.data.redis.core.RedisCallback<String>) connection -> connection.ping())).isEqualTo("PONG");
        PendingReservation delayed = store.findByRequestId(activity, request).orElse(null);
        if (delayed != null) {
            await().atMost(Duration.ofSeconds(10)).until(() -> System.currentTimeMillis() > delayed.expireAt());
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                orphanRecovery.recover();
                assertThat(durable.findByRequestId(request))
                    .hasValueSatisfying(row -> assertThat(row.status()).isEqualTo(StockReservationStatus.CANCELLED));
            });
        }
        System.out.println("FLASHGUARD_REDIS_OUTAGE delayedReservation=" + (delayed != null) + " stock=" + store.getAvailableStock(activity));
        assertThat(store.getAvailableStock(activity)).isEqualTo(200);
        assertThat(orderCount()).isZero();
        assertThat(activities.findById(activity).orElseThrow().availableStock()).isEqualTo(200);
    }

    @Test void producerProcessHaltBeforeLocalTransactionRollsBack() throws Exception {
        producerCrash(false);
    }

    @Test void producerProcessHaltAfterLocalCommitRecovers() throws Exception {
        producerCrash(true);
    }

    private void producerCrash(boolean afterCommit) throws Exception {
        String producerGroup = "fg_crash_p_" + UUID.randomUUID().toString().replace("-", "");
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger recoveryLocalExecutions = new AtomicInteger();
        AtomicInteger recoveryDecisions = new AtomicInteger();
        AtomicInteger deliveries = new AtomicInteger();
        ReservationMessageConsumer actualConsumer = productionConsumer();
        startConsumer(group, (messages, context) -> {
            deliveries.addAndGet(messages.size());
            return actualConsumer.consumeMessage(messages, context);
        });
        PendingReservation pending = reserve(1, afterCommit ? Duration.ofMinutes(5) : Duration.ofSeconds(30));
        String mode = afterCommit ? "producer-after-commit" : "producer-before-local";
        Path checkpoint = broker.directory().resolve(topic + ".checkpoint");
        Process worker = launchWorker(mode, producerGroup, pending, checkpoint);
        awaitCrash(worker, checkpoint, mode + ":" + pending.reservationId());
        // The child was the sole producer in this group. No live recovery producer
        // existed before its process exited, and the half message is still invisible.
        assertThat(producers).isEmpty();
        assertThat(checks.get()).isZero();
        assertThat(deliveries.get()).isZero();
        assertThat(orderCount()).isZero();
        assertThat(store.getAvailableStock(activity)).isEqualTo(199);
        assertThat(activities.findById(activity).orElseThrow().availableStock()).isEqualTo(200);
        if (afterCommit) assertThat(reservations.findByReservationId(pending.reservationId()))
            .hasValueSatisfying(row -> assertThat(row.status()).isEqualTo(StockReservationStatus.MQ_COMMITTED));
        else assertThat(reservations.findByReservationId(pending.reservationId())).isEmpty();

        // Cold restart: a fresh client registers the same group and answers checks.
        // It sends no new transactional or ordinary application messages.
        producer(producerGroup, new TransactionListener() {
            @Override public LocalTransactionState executeLocalTransaction(Message m, Object arg) {
                recoveryLocalExecutions.incrementAndGet();
                return LocalTransactionState.UNKNOW;
            }
            @Override public LocalTransactionState checkLocalTransaction(MessageExt m) {
                checks.incrementAndGet();
                LocalTransactionState decision = listener.checkLocalTransaction(m);
                if (decision == (afterCommit ? LocalTransactionState.COMMIT_MESSAGE : LocalTransactionState.ROLLBACK_MESSAGE))
                    recoveryDecisions.incrementAndGet();
                return decision;
            }
        });
        if (afterCommit) {
            confirmed(pending); assertSingleOrderAndDebit(pending);
        } else {
            // The process died in the local callback after the broker persisted the half message.
            if (System.currentTimeMillis() < pending.expireAt())
                assertThat(reservations.findByReservationId(pending.reservationId())).isEmpty();
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(reservations.findByReservationId(pending.reservationId()))
                    .hasValueSatisfying(row -> assertThat(row.status()).isEqualTo(StockReservationStatus.CANCELLED)));
            assertThat(orderCount()).isZero(); assertThat(store.getAvailableStock(activity)).isEqualTo(200);
            assertThat(activities.findById(activity).orElseThrow().availableStock()).isEqualTo(200);
            assertThat(deliveries.get()).isZero();
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> recoveryDecisions.get() > 0);
        assertThat(checks.get()).isPositive();
        assertThat(recoveryLocalExecutions.get()).isZero();
        System.out.println("FLASHGUARD_COLD_PRODUCER_RECOVERY mode=" + mode + " childExit=" + worker.exitValue()
            + " checks=" + checks.get() + " terminalDecisions=" + recoveryDecisions.get() + " newLocalTransactions="
            + recoveryLocalExecutions.get() + " orders=" + orderCount() + " redisStock=" + store.getAvailableStock(activity));
    }

    @Test void consumerProcessHaltBeforeCommitRedelivers() throws Exception {
        PendingReservation pending = reserve(1, Duration.ofMinutes(5));
        Path checkpoint = broker.directory().resolve(topic + ".checkpoint");
        Process worker = launchWorker("consumer-before-commit", "fg_worker_" + UUID.randomUUID().toString().replace("-", ""), pending, checkpoint);
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            if (!worker.isAlive()) throw new IllegalStateException("Consumer worker exited before readiness; inspect " + broker.directory());
            return Files.exists(Path.of(checkpoint + ".ready"));
        });
        TransactionMQProducer producer = producer(listener);
        publish(producer, pending);
        awaitCrash(worker, checkpoint, "consumer-before-commit:" + pending.reservationId());
        assertThat(orderCount()).isZero();
        assertThat(reservations.findByReservationId(pending.reservationId()).orElseThrow().status()).isEqualTo(StockReservationStatus.MQ_COMMITTED);
        startConsumer(group, productionConsumer());
        confirmed(pending); assertSingleOrderAndDebit(pending);
    }

    private Process launchWorker(String mode, String producerGroup, PendingReservation pending, Path checkpoint) throws Exception {
        Path reservationFile = broker.directory().resolve(topic + ".json");
        Files.writeString(reservationFile, json.writeValueAsString(pending));
        return broker.launch(topic + "-worker", MqCrashWorker.class.getName(), List.of(mode, checkpoint.toString(), reservationFile.toString(),
            "--spring.datasource.url=" + MYSQL.getJdbcUrl(), "--spring.datasource.username=" + MYSQL.getUsername(),
            "--spring.datasource.password=" + MYSQL.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
            "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--flashguard.mq.enabled=true",
            "--flashguard.mq.consumer-enabled=false", "--flashguard.scheduling.enabled=false",
            "--flashguard.participation.mode=mysql", "--flashguard.mq.nameserver=" + broker.nameserverAddress(),
            "--flashguard.mq.producer-group=" + producerGroup, "--flashguard.mq.consumer-group=" + group,
            "--flashguard.mq.topic=" + topic, "--spring.main.banner-mode=off"));
    }
    private void awaitCrash(Process worker, Path checkpoint, String expected) {
        await().atMost(Duration.ofSeconds(60)).until(() -> !worker.isAlive());
        assertThat(worker.exitValue()).isEqualTo(137);
        try { assertThat(Files.readString(checkpoint)).isEqualTo(expected); }
        catch (java.io.IOException failure) { throw new AssertionError("Crash checkpoint missing", failure); }
    }

    private TransactionListener unknownListener(boolean commit, AtomicInteger checks) {
        return new TransactionListener() {
            @Override public LocalTransactionState executeLocalTransaction(Message message, Object arg) {
                if (commit) assertThat(listener.executeLocalTransaction(message, arg)).isEqualTo(LocalTransactionState.COMMIT_MESSAGE);
                return LocalTransactionState.UNKNOW;
            }
            @Override public LocalTransactionState checkLocalTransaction(MessageExt message) {
                checks.incrementAndGet(); return listener.checkLocalTransaction(message);
            }
        };
    }
    private PendingReservation reserve(long user, Duration ttl) {
        RedisReservationStore timed = new RedisReservationStore(redis, ttl);
        return timed.reserve(activity, user, UUID.randomUUID().toString()).reservation();
    }
    private TransactionMQProducer producer(TransactionListener transactionListener) throws Exception {
        return producer("fg_it_p_" + UUID.randomUUID().toString().replace("-", ""), transactionListener);
    }
    private TransactionMQProducer producer(String producerGroup, TransactionListener transactionListener) throws Exception {
        TransactionMQProducer result = new RecoverableTransactionProducer(producerGroup, topic);
        result.setNamesrvAddr(broker.nameserverAddress()); result.setVipChannelEnabled(false);
        result.setInstanceName(UUID.randomUUID().toString()); result.setSendMsgTimeout(10000);
        result.setTransactionListener(transactionListener); result.start(); producers.add(result); return result;
    }
    private void publish(TransactionMQProducer producer, PendingReservation pending) {
        new RocketMqReservationPublisher(producer, codec, durable, topic, meters).publish(pending);
    }
    private ReservationMessageConsumer productionConsumer() { return new ReservationMessageConsumer(codec, durable, orderConsumer, meters, 1); }
    private DefaultMQPushConsumer startConsumer(String consumerGroup, MessageListenerConcurrently callback) throws Exception {
        return startConsumer(consumerGroup, callback, 2);
    }
    private DefaultMQPushConsumer startConsumer(String consumerGroup, MessageListenerConcurrently callback, int retries) throws Exception {
        DefaultMQPushConsumer result = new DefaultMQPushConsumer(consumerGroup);
        result.setNamesrvAddr(broker.nameserverAddress()); result.setVipChannelEnabled(false);
        result.setInstanceName(UUID.randomUUID().toString());
        result.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        result.setConsumeThreadMin(1); result.setConsumeThreadMax(2); result.setConsumeMessageBatchMaxSize(1);
        result.setMaxReconsumeTimes(retries); result.setAwaitTerminationMillisWhenShutdown(3000);
        result.subscribe(topic, "*"); result.registerMessageListener(callback); result.start(); consumers.add(result); return result;
    }
    private void confirmed(PendingReservation pending) {
        await().atMost(Duration.ofSeconds(60)).ignoreExceptions().untilAsserted(() ->
            assertThat(reservations.findByReservationId(pending.reservationId()).orElseThrow().status()).isEqualTo(StockReservationStatus.CONFIRMED));
    }
    private long orderCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE activity_id=?", Long.class, activity); }
    private void assertSingleOrderAndDebit(PendingReservation pending) {
        assertThat(orderCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE reservation_id=?", Long.class, pending.reservationId())).isEqualTo(1);
        assertThat(activities.findById(activity).orElseThrow().availableStock()).isEqualTo(199);
        assertThat(store.getAvailableStock(activity)).isEqualTo(199);
    }
}
