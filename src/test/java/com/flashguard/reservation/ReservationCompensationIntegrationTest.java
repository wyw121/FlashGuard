package com.flashguard.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.time.Duration;

import com.flashguard.activity.ActivityMapper;
import com.flashguard.order.OrderMapper;
import com.flashguard.order.ReservationConsumerService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.flashguard.support.MySqlContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {"flashguard.mq.enabled=false", "flashguard.compensation.scan-interval=24h",
    "flashguard.compensation.orphan-scan-interval=24h", "flashguard.compensation.fence-scan-interval=24h"})
@org.springframework.context.annotation.Import(ReservationCompensationIntegrationTest.LocalTransactionConfiguration.class)
class ReservationCompensationIntegrationTest extends MySqlContainerSupport {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class LocalTransactionConfiguration {
        @org.springframework.context.annotation.Bean
        com.flashguard.mq.ReservationLocalTransactionService concurrentLocalTransaction(StockReservationMapper mapper) {
            return new com.flashguard.mq.ReservationLocalTransactionService(mapper);
        }
    }
    @Autowired com.flashguard.mq.ReservationLocalTransactionService localTransaction;
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.8"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        REDIS.start();
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @org.junit.jupiter.api.AfterAll
    static void stopRedis() { REDIS.stop(); }

    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired ConflictReservationRecoveryService conflictRecovery;
    @Autowired ReservationCancellationFenceService fences;
    @Autowired StockReservationMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.flashguard.reconciliation.ReconciliationIssueMapper issues;
    @Autowired StockReservationService durable;
    @Autowired ReservationTimeoutCompensationService compensation;
    @Autowired RedisReservationStore store;
    @Autowired StringRedisTemplate redis;
    @Autowired ActivityMapper activities;
    @Autowired OrderMapper orders;
    @Autowired ReservationConsumerService consumer;

    @BeforeEach
    void seed() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbc.update("DELETE FROM reservation_cancellation_fence");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM stock_reservation");
        jdbc.update("DELETE FROM activity_stock");
        jdbc.update("DELETE FROM activity");
        jdbc.update("INSERT INTO activity(id,name,start_time,end_time,status) "
            + "VALUES (5101,'compensation',UTC_TIMESTAMP()-INTERVAL 1 HOUR,UTC_TIMESTAMP()+INTERVAL 1 HOUR,'ACTIVE')");
        jdbc.update("INSERT INTO activity_stock(activity_id,initial_stock,available_stock) VALUES (5101,50,50)");
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 2, unit = java.util.concurrent.TimeUnit.MINUTES)
    void twentyDistinctConcurrentLocalTransactionsCommitWithoutDeadlocks() throws Exception {
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(localTransaction)).isTrue();
        store.warmUp(activities.findById(5101).orElseThrow());
        int attempts = 20;
        var pending = new java.util.ArrayList<PendingReservation>();
        for(int i=0;i<attempts;i++) pending.add(store.reserve(5101,10000+i,"concurrent-local-"+i).reservation());
        assertThat(store.getAvailableStock(5101)).isEqualTo(30);
        var ready = new java.util.concurrent.CountDownLatch(attempts);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(attempts);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
        try {
            for(var reservation : pending) futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    var record = localTransaction.commit(reservation);
                    return record.status() == StockReservationStatus.MQ_COMMITTED ? null
                        : reservation.reservationId()+": unexpected status "+record.status();
                } catch(Exception failure) {
                    Throwable cause=failure;
                    while(cause.getCause()!=null) cause=cause.getCause();
                    return reservation.reservationId()+": "+cause.getClass().getSimpleName()+": "+cause.getMessage();
                }
            }));
            assertThat(ready.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var failures = new java.util.ArrayList<String>();
            for(var future:futures) {
                String failure=future.get(60,java.util.concurrent.TimeUnit.SECONDS);
                if(failure!=null) failures.add(failure);
            }
            System.out.println("FLASHGUARD_CONCURRENT_LOCAL_TX attempts="+attempts+" failures="+failures.size()+" details="+failures);
            assertThat(failures).as("distinct valid local callbacks must all commit without deadlock or retries").isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_reservation WHERE activity_id=5101 AND status='MQ_COMMITTED'",Long.class)).isEqualTo(20);
            for(var reservation:pending) assertThat(mapper.findByReservationId(reservation.reservationId()))
                .hasValueSatisfying(row->assertThat(row.status()).isEqualTo(StockReservationStatus.MQ_COMMITTED));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_cancellation_fence",Long.class)).isZero();
            assertThat(orders.countByActivity(5101)).isZero();
            assertThat(activities.findById(5101).orElseThrow().availableStock()).isEqualTo(50);
            assertThat(store.getAvailableStock(5101)).isEqualTo(30);
            assertThat(store.countPendingReservations(5101)).isEqualTo(20);
        } finally {start.countDown();executor.shutdownNow();}
    }
    @Test
    void crossActivityRequestRaceCancelsOnlyLoserExactlyOnceAndFencesLateCallback() {
        jdbc.update("INSERT INTO activity(id,name,start_time,end_time,status) VALUES (5102,'loser',UTC_TIMESTAMP()-INTERVAL 1 HOUR,UTC_TIMESTAMP()+INTERVAL 1 HOUR,'ACTIVE')");
        jdbc.update("INSERT INTO activity_stock(activity_id,initial_stock,available_stock) VALUES (5102,50,50)");
        store.warmUp(activities.findById(5101).orElseThrow());
        var shortLived = new RedisReservationStore(redis,Duration.ofMillis(1));
        shortLived.warmUp(activities.findById(5102).orElseThrow());
        var winner = store.reserve(5101,99,"shared-request").reservation();
        var loser = shortLived.reserve(5102,88,"shared-request").reservation();
        durable.createPending(winner);
        var forged = new PendingReservation("wrong-reservation",loser.activityId(),loser.userId(),loser.requestId(),
            loser.status(),loser.createdAt(),loser.expireAt());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.releaseExpected(forged)).isInstanceOf(ReservationStateException.class);
        assertThat(store.getAvailableStock(5102)).isEqualTo(49);
        consumer.consumeReservation(winner.reservationId(),winner.reservationId());
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> System.currentTimeMillis() >= loser.expireAt());
        var scanner = new RedisOrphanRecoveryJob(activities,shortLived,durable,compensation,issues,conflictRecovery);
        scanner.recover();
        scanner.recover();
        for(int i=0;i<5;i++) conflictRecovery.recoverConflict(loser);
        assertThat(store.getAvailableStock(5101)).isEqualTo(49);
        assertThat(store.getAvailableStock(5102)).isEqualTo(50);
        assertThat(orders.countByActivity(5101)).isEqualTo(1);
        assertThat(orders.countByActivity(5102)).isZero();
        assertThat(durable.findByRequestId("shared-request").orElseThrow().reservationId()).isEqualTo(winner.reservationId());
        assertThat(durable.findCancellationFence(loser.reservationId()).orElseThrow().status()).isEqualTo(StockReservationStatus.CANCELLED);
        var delayed = new com.flashguard.mq.ReservationLocalTransactionService(mapper).commit(loser);
        assertThat(delayed.status()).isEqualTo(StockReservationStatus.CANCELLED);
        assertThat(mapper.findByReservationId(loser.reservationId())).isEmpty();
    }

    @Test
    void conflictFenceRecoversCrashAfterRedisReleaseBeforeDbCompletion() {
        jdbc.update("INSERT INTO activity(id,name,start_time,end_time,status) VALUES (5102,'loser',UTC_TIMESTAMP()-INTERVAL 1 HOUR,UTC_TIMESTAMP()+INTERVAL 1 HOUR,'ACTIVE')");
        jdbc.update("INSERT INTO activity_stock(activity_id,initial_stock,available_stock) VALUES (5102,50,50)");
        store.warmUp(activities.findById(5101).orElseThrow());
        var shortLived = new RedisReservationStore(redis,Duration.ofMillis(1));
        shortLived.warmUp(activities.findById(5102).orElseThrow());
        var winner = store.reserve(5101,99,"shared-crash").reservation();
        var loser = shortLived.reserve(5102,88,"shared-crash").reservation();
        durable.createPending(winner);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> System.currentTimeMillis() >= loser.expireAt());
        fences.claim(loser);
        assertThat(store.releaseExpected(loser)).isTrue();
        conflictRecovery.retryPendingFences();
        conflictRecovery.retryPendingFences();
        assertThat(store.getAvailableStock(5102)).isEqualTo(50);
        assertThat(store.getAvailableStock(5101)).isEqualTo(49);
        assertThat(durable.findCancellationFence(loser.reservationId()).orElseThrow().status()).isEqualTo(StockReservationStatus.CANCELLED);
    }

    @Test
    void concurrentLateCallbackAndConflictFenceCannotCommitLosingReservation() throws Exception {
        jdbc.update("INSERT INTO activity(id,name,start_time,end_time,status) VALUES (5102,'loser',UTC_TIMESTAMP()-INTERVAL 1 HOUR,UTC_TIMESTAMP()+INTERVAL 1 HOUR,'ACTIVE')");
        jdbc.update("INSERT INTO activity_stock(activity_id,initial_stock,available_stock) VALUES (5102,50,50)");
        store.warmUp(activities.findById(5101).orElseThrow());
        var shortLived = new RedisReservationStore(redis,Duration.ofMillis(1));
        shortLived.warmUp(activities.findById(5102).orElseThrow());
        var winner = store.reserve(5101,99,"shared-concurrent").reservation();
        var loser = shortLived.reserve(5102,88,"shared-concurrent").reservation();
        durable.createPending(winner);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> System.currentTimeMillis() >= loser.expireAt());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var callback = executor.submit(() -> {
                start.await();
                try {
                    var result = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                        .execute(status -> new com.flashguard.mq.ReservationLocalTransactionService(mapper).commit(loser));
                    assertThat(result.status()).isIn(StockReservationStatus.CANCEL_PENDING,StockReservationStatus.CANCELLED);
                } catch (IllegalStateException | org.springframework.dao.DataAccessException safelyRejected) { }
                return null;
            });
            var cancellation = executor.submit(() -> {start.await(); try { conflictRecovery.recoverConflict(loser); }
                catch (org.springframework.dao.DataAccessException retryableLockConflict) { } return null;});
            start.countDown();
            callback.get(15,java.util.concurrent.TimeUnit.SECONDS);
            cancellation.get(15,java.util.concurrent.TimeUnit.SECONDS);
            conflictRecovery.recoverConflict(loser);
            assertThat(mapper.findByReservationId(loser.reservationId())).isEmpty();
            assertThat(durable.findCancellationFence(loser.reservationId()).orElseThrow().status()).isEqualTo(StockReservationStatus.CANCELLED);
            assertThat(store.getAvailableStock(5101)).isEqualTo(49);
            assertThat(store.getAvailableStock(5102)).isEqualTo(50);
        } finally {executor.shutdownNow();}
    }

    @Test
    void oneHundredConsumerDeliveriesCreateOneOrderAndDeductOnce() {
        store.warmUp(activities.findById(5101).orElseThrow());
        var record = durable.createPending(store.reserve(5101, 99, "duplicate-delivery").reservation());
        String orderNo = consumer.consumeReservation(record.reservationId(), "same-message").orderNo();
        for (int attempt = 1; attempt < 100; attempt++) {
            assertThat(consumer.consumeReservation(record.reservationId(), "same-message").orderNo()).isEqualTo(orderNo);
        }
        assertThat(orders.countByActivity(5101)).isEqualTo(1);
        assertThat(activities.findById(5101).orElseThrow().availableStock()).isEqualTo(49);
    }

    @Test
    void failedInventoryUpdateRollsBackInsertedOrderAndReservationTransition() {
        store.warmUp(activities.findById(5101).orElseThrow());
        var record = durable.createPending(store.reserve(5101, 99, "empty-db").reservation());
        jdbc.update("UPDATE activity_stock SET available_stock=0 WHERE activity_id=5101");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.consumeReservation(record.reservationId(), "message"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(orders.countByActivity(5101)).isZero();
        assertThat(mapper.findByReservationId(record.reservationId()).orElseThrow().status()).isEqualTo(StockReservationStatus.PENDING);
    }

    @Test
    void cancelledAttemptDoesNotPreventANewRequestByTheSameUser() {
        store.warmUp(activities.findById(5101).orElseThrow());
        var first = durable.createPending(store.reserve(5101, 99, "attempt-one").reservation());
        compensation.compensateReservation(first);
        var second = durable.createPending(store.reserve(5101, 99, "attempt-two").reservation());
        consumer.consumeReservation(second.reservationId(), "message-two");
        assertThat(mapper.findByReservationId(first.reservationId()).orElseThrow().status())
            .isEqualTo(StockReservationStatus.CANCELLED);
        assertThat(mapper.findByReservationId(second.reservationId()).orElseThrow().status())
            .isEqualTo(StockReservationStatus.CONFIRMED);
        assertThat(orders.countByActivity(5101)).isEqualTo(1);
        assertThat(store.getAvailableStock(5101)).isEqualTo(49);
    }

    @Test
    void redisOnlyCrashWindowBecomesDurableAndCancelled() {
        RedisReservationStore shortLived = new RedisReservationStore(redis, Duration.ofMillis(1));
        shortLived.warmUp(activities.findById(5101).orElseThrow());
        PendingReservation pending = shortLived.reserve(5101, 99, "orphan").reservation();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() ->
            !shortLived.findExpiredReservations(5101, System.currentTimeMillis(), 0, 200).isEmpty());
        assertThat(mapper.findByReservationId(pending.reservationId())).isEmpty();
        new RedisOrphanRecoveryJob(activities, shortLived, durable, compensation, issues).recover();
        assertThat(mapper.findByReservationId(pending.reservationId()).orElseThrow().status())
            .isEqualTo(StockReservationStatus.CANCELLED);
        assertThat(store.getAvailableStock(5101)).isEqualTo(50);
    }

    @Test
    void resumesAfterRedisReleaseBeforeDatabaseCompletion() {
        store.warmUp(activities.findById(5101).orElseThrow());
        PendingReservation pending = store.reserve(5101, 99, "crash-after-release").reservation();
        durable.createPending(pending);
        jdbc.update("UPDATE stock_reservation SET expire_at=UTC_TIMESTAMP()-INTERVAL 1 MINUTE");
        mapper.markAsCancelPending(pending.reservationId(), "original");
        assertThat(store.releaseByRequestId(5101, pending.requestId())).isTrue();
        // Recreate the scanner, as after a process restart: only MySQL and Redis state survives.
        new ReservationTimeoutCompensationService(durable, orders, store,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), 200).compensateExpiredReservations();
        assertThat(mapper.findByReservationId(pending.reservationId()).orElseThrow().status())
            .isEqualTo(StockReservationStatus.CANCELLED);
        assertThat(store.getAvailableStock(5101)).isEqualTo(50);
        assertThat(orders.countByActivity(5101)).isZero();
    }

    @Test
    void cancellationAndConsumerRaceNeverBothReleaseAndCreateAnOrder() throws Exception {
        store.warmUp(activities.findById(5101).orElseThrow());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 20; attempt++) {
                PendingReservation pending = store.reserve(5101, attempt + 1, "race-" + attempt).reservation();
                StockReservationRecord record = durable.createPending(pending);
                var start = new java.util.concurrent.CountDownLatch(1);
                var consume = executor.submit(() -> {
                    start.await();
                    try { consumer.consumeReservation(record.reservationId(), "race-message"); }
                    catch (IllegalStateException cancelled) {
                        assertThat(mapper.findByReservationId(record.reservationId()).orElseThrow().status())
                            .isIn(StockReservationStatus.CANCEL_PENDING, StockReservationStatus.CANCELLED);
                    }
                    return null;
                });
                var cancel = executor.submit(() -> { start.await(); compensation.compensateReservation(record); return null; });
                start.countDown();
                consume.get(15, java.util.concurrent.TimeUnit.SECONDS);
                cancel.get(15, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(mapper.findByReservationId(record.reservationId()).orElseThrow().status())
                    .isIn(StockReservationStatus.CONFIRMED, StockReservationStatus.CANCELLED);
            }
            long count = orders.countByActivity(5101);
            assertThat(store.getAvailableStock(5101)).isEqualTo(50 - count);
            assertThat(activities.findById(5101).orElseThrow().availableStock()).isEqualTo((int) (50 - count));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void repeatedDiscrepancyKeepsAnAuditableObservationCount() {
        jdbc.update("DELETE FROM reconciliation_issue");
        issues.insert(5101, "Redis stock drift = -1");
        issues.insert(5101, "Redis stock drift = -1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_issue", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT observations FROM reconciliation_issue", Long.class)).isEqualTo(2);
    }

    @Test
    void scannerRecoversEveryNonterminalCancellationStateAndPreservesMessageKey() {
        for (String state : List.of("PENDING", "MQ_COMMITTED", "CANCEL_PENDING", "COMPENSATION_FAILED")) {
            int user = state.hashCode();
            jdbc.update("INSERT INTO stock_reservation(reservation_id,activity_id,user_id,request_id,status,message_key,expire_at) "
                + "VALUES (?,5101,?,?,?,'original-message',UTC_TIMESTAMP()-INTERVAL 1 MINUTE)", state, user, state, state);
        }
        assertThat(mapper.findExpiredConsumable(LocalDateTime.now(ZoneOffset.UTC), 100)).hasSize(4);
        for (String state : List.of("PENDING", "MQ_COMMITTED", "CANCEL_PENDING", "COMPENSATION_FAILED")) {
            assertThat(mapper.markAsCancelPending(state, "compensation")).isEqualTo(1);
            assertThat(mapper.markAsCancelled(state, "compensation")).isEqualTo(1);
            assertThat(mapper.findByReservationId(state).orElseThrow().messageKey()).isEqualTo("original-message");
        }
        assertThat(mapper.findExpiredConsumable(LocalDateTime.now(ZoneOffset.UTC), 100)).isEmpty();
    }

    @Test
    void cancellationCannotClaimAReservationWithAnOrderOrAConfirmedReservation() {
        jdbc.update("INSERT INTO stock_reservation(reservation_id,activity_id,user_id,request_id,status,expire_at) "
            + "VALUES ('with-order',5101,1,'with-order','MQ_COMMITTED',UTC_TIMESTAMP()-INTERVAL 1 MINUTE)");
        jdbc.update("INSERT INTO orders(order_no,request_id,reservation_id,activity_id,user_id,status) "
            + "VALUES ('order-1','with-order','with-order',5101,1,'CONFIRMED')");
        assertThat(mapper.markAsCancelPending("with-order", "compensation")).isZero();
        assertThat(mapper.markAsCompensationFailed("with-order", "compensation")).isZero();
        mapper.markAsConfirmed("with-order", "confirmed");
        assertThat(mapper.markAsCancelPending("with-order", "compensation")).isZero();
    }
}
