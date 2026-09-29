package com.flashguard.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.flashguard.activity.ActivityStatus;
import com.flashguard.activity.ActivityView;
import com.flashguard.support.RedisContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class RedisReservationStoreTest extends RedisContainerSupport {

    private static final long ACTIVITY_ID = 1001L;
    private static final String PREFIX = "fg:{activity:1001}:";

    private RedisReservationStore store;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        store = new RedisReservationStore(redis, Duration.ofSeconds(120));
    }

    @Test
    void malformedOrphanDoesNotBlockAHealthyOrphanInTheSamePage() {
        RedisReservationStore shortLived = new RedisReservationStore(redis, Duration.ofMillis(1));
        shortLived.warmUp(activeActivity(ACTIVITY_ID, 10, 10));
        shortLived.reserve(ACTIVITY_ID, 2001, "bad");
        PendingReservation good = shortLived.reserve(ACTIVITY_ID, 2002, "good").reservation();
        redis.opsForHash().put(PREFIX + "requests", "bad", "invalid-json");
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() ->
            redis.opsForZSet().count(PREFIX + "pending", 0, System.currentTimeMillis()) == 2);
        var activities = org.mockito.Mockito.mock(com.flashguard.activity.ActivityMapper.class);
        var durable = org.mockito.Mockito.mock(StockReservationService.class);
        var compensation = org.mockito.Mockito.mock(ReservationTimeoutCompensationService.class);
        var issues = org.mockito.Mockito.mock(com.flashguard.reconciliation.ReconciliationIssueMapper.class);
        var record = new StockReservationService(org.mockito.Mockito.mock(StockReservationMapper.class)).toPendingRecord(good);
        org.mockito.Mockito.when(activities.findIdsAfter(0, 1)).thenReturn(List.of(ACTIVITY_ID));
        org.mockito.Mockito.when(durable.createPending(good)).thenReturn(record);
        new RedisOrphanRecoveryJob(activities, shortLived, durable, compensation, issues).recover();
        org.mockito.Mockito.verify(compensation).compensateReservation(record);
        org.mockito.Mockito.verify(issues).insert(org.mockito.ArgumentMatchers.eq(ACTIVITY_ID),
            org.mockito.ArgumentMatchers.contains("bad"));
    }

    @Test
    void releaseIsIdempotentAndDoesNotReleaseANewerReservationForTheSameRequest() {
        store.warmUp(activeActivity(ACTIVITY_ID, 10, 10));
        store.reserve(ACTIVITY_ID, 2001L, "release-1");
        for (int i = 0; i < 100; i++) {
            assertThat(store.releaseByRequestId(ACTIVITY_ID, "release-1")).isTrue();
        }
        assertThat(remaining(ACTIVITY_ID)).isEqualTo(10);
        assertThat(store.countPendingReservations(ACTIVITY_ID)).isZero();
        // The cancelled request remains a tombstone, so a delayed retry cannot reserve again.
        assertThat(store.reserve(ACTIVITY_ID, 2001L, "release-1").decision())
            .isEqualTo(ReservationDecision.REQUEST_CONFLICT);
        assertThat(store.reserve(ACTIVITY_ID, 2001L, "release-2").decision())
            .isEqualTo(ReservationDecision.SUCCESS);
        assertThat(store.releaseByRequestId(ACTIVITY_ID, "release-1")).isTrue();
        assertThat(remaining(ACTIVITY_ID)).isEqualTo(9);
    }

    @Test
    void invalidReleaseDoesNotPartiallyDeleteReservation() {
        store.warmUp(activeActivity(ACTIVITY_ID, 10, 10));
        store.reserve(ACTIVITY_ID, 2001L, "release-1");
        redis.opsForHash().put(PREFIX + "stock", "availableStock", "10");
        assertThatThrownBy(() -> store.releaseByRequestId(ACTIVITY_ID, "release-1"))
            .isInstanceOf(ReservationStoreException.class);
        assertThat(redis.opsForHash().hasKey(PREFIX + "requests", "release-1")).isTrue();
        assertThat(redis.opsForHash().hasKey(PREFIX + "users", "2001")).isTrue();
        assertThat(store.countPendingReservations(ACTIVITY_ID)).isEqualTo(1);
    }

    @Test
    void warmupUsesAvailableDatabaseStockAndNeverOverwritesAnInitializedActivity() {
        ActivityView snapshot = activeActivity(ACTIVITY_ID, 500, 7);

        assertThat(store.warmUp(snapshot)).isEqualTo(WarmUpResult.INITIALIZED);
        assertThat(store.reserve(ACTIVITY_ID, 2001L, "request-1").decision())
            .isEqualTo(ReservationDecision.SUCCESS);
        assertThat(store.warmUp(activeActivity(ACTIVITY_ID, 500, 99)))
            .isEqualTo(WarmUpResult.ALREADY_INITIALIZED);

        assertThat(redis.<String, String>opsForHash().get(PREFIX + "stock", "initialStock"))
            .isEqualTo("7");
        assertThat(remaining(ACTIVITY_ID)).isEqualTo(6);
    }

    @Test
    void partialWarmupStateIsRejectedWithoutRebuildingIt() {
        redis.opsForHash().put(PREFIX + "stock", "schemaVersion", "1");

        assertThatThrownBy(() -> store.warmUp(activeActivity(ACTIVITY_ID, 10, 10)))
            .isInstanceOf(ReservationStateException.class);
        assertThat(redis.hasKey(PREFIX + "requests")).isFalse();
    }

    @Test
    void warmupRejectsNonActiveActivities() {
        ActivityView draft = new ActivityView(
            ACTIVITY_ID,
            "Draft",
            LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1),
            LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1),
            ActivityStatus.DRAFT,
            10,
            10
        );

        assertThatThrownBy(() -> store.warmUp(draft)).isInstanceOf(IllegalArgumentException.class);
        assertThat(redis.hasKey(PREFIX + "stock")).isFalse();
    }

    @Test
    void reportsNotReadyNotStartedEndedAndSoldOutWithoutCreatingReservations() {
        assertThat(store.reserve(ACTIVITY_ID, 2001L, "not-ready").decision())
            .isEqualTo(ReservationDecision.NOT_READY);

        long futureActivity = 1002L;
        store.warmUp(activity(futureActivity, 1, 1, 5, 10));
        assertThat(store.reserve(futureActivity, 2001L, "too-early").decision())
            .isEqualTo(ReservationDecision.NOT_STARTED);

        long endedActivity = 1003L;
        store.warmUp(activity(endedActivity, 1, 1, -10, -5));
        assertThat(store.reserve(endedActivity, 2001L, "too-late").decision())
            .isEqualTo(ReservationDecision.ENDED);

        long emptyActivity = 1004L;
        store.warmUp(activeActivity(emptyActivity, 0, 0));
        assertThat(store.reserve(emptyActivity, 2001L, "sold-out").decision())
            .isEqualTo(ReservationDecision.SOLD_OUT);
        assertThat(store.findByRequestId(emptyActivity, "sold-out")).isEmpty();
    }

    @Test
    void replayReturnsTheOriginalPendingReservationWithoutDeductingOrExtendingIt() {
        store.warmUp(activeActivity(ACTIVITY_ID, 10, 10));

        ReservationResult first = store.reserve(ACTIVITY_ID, 2001L, "request-1");
        ReservationResult replay = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            replay = store.reserve(ACTIVITY_ID, 2001L, "request-1");
            assertThat(replay.decision()).isEqualTo(ReservationDecision.REPLAY);
            assertThat(replay.reservation()).isEqualTo(first.reservation());
        }

        assertThat(first.decision()).isEqualTo(ReservationDecision.SUCCESS);
        assertThat(store.findByRequestId(ACTIVITY_ID, "request-1"))
            .contains(first.reservation());
        assertThat(remaining(ACTIVITY_ID)).isEqualTo(9);
    }

    @Test
    void requestIdConflictAndDuplicateUserAreDistinctAndDoNotDeductAgain() {
        store.warmUp(activeActivity(ACTIVITY_ID, 10, 10));
        assertThat(store.reserve(ACTIVITY_ID, 2001L, "request-1").decision())
            .isEqualTo(ReservationDecision.SUCCESS);

        assertThat(store.reserve(ACTIVITY_ID, 2002L, "request-1").decision())
            .isEqualTo(ReservationDecision.REQUEST_CONFLICT);
        assertThat(store.reserve(ACTIVITY_ID, 2001L, "request-2").decision())
            .isEqualTo(ReservationDecision.DUPLICATE);
        assertThat(remaining(ACTIVITY_ID)).isEqualTo(9);
        assertThat(store.findByRequestId(ACTIVITY_ID, "request-2")).isEmpty();
    }

    @Test
    void requestIdsAreCaseSensitiveAndActivitiesAreIsolated() {
        store.warmUp(activeActivity(ACTIVITY_ID, 2, 2));
        store.warmUp(activeActivity(1002L, 1, 1));

        assertThat(store.reserve(ACTIVITY_ID, 2001L, "Case").decision())
            .isEqualTo(ReservationDecision.SUCCESS);
        assertThat(store.reserve(ACTIVITY_ID, 2002L, "case").decision())
            .isEqualTo(ReservationDecision.SUCCESS);
        assertThat(store.reserve(1002L, 2001L, "Case").decision())
            .isEqualTo(ReservationDecision.SUCCESS);

        assertThat(remaining(ACTIVITY_ID)).isZero();
        assertThat(remaining(1002L)).isZero();
    }

    @Test
    void requestIdsNeverConflictWithInternalMetadataNames() {
        store.warmUp(activeActivity(ACTIVITY_ID, 1, 1));

        ReservationResult result = store.reserve(ACTIVITY_ID, 2001L, "__schemaVersion");

        assertThat(result.decision()).isEqualTo(ReservationDecision.SUCCESS);
        assertThat(store.findByRequestId(ACTIVITY_ID, "__schemaVersion"))
            .contains(result.reservation());
        assertThat(redis.opsForZSet().score(PREFIX + "pending", "__schemaVersion"))
            .isEqualTo((double) result.reservation().expireAt());
    }

    @Test
    void idsBeyondLuaIntegerPrecisionRemainExactInStorageAndReplay() {
        long userId = 9_007_199_254_740_993L;
        store.warmUp(activeActivity(ACTIVITY_ID, 1, 1));

        PendingReservation reservation = store.reserve(ACTIVITY_ID, userId, "large-user").reservation();

        assertThat(reservation.userId()).isEqualTo("9007199254740993");
        assertThat(store.reserve(ACTIVITY_ID, userId, "large-user").reservation())
            .isEqualTo(reservation);
        assertThat(redis.<String, String>opsForHash().get(PREFIX + "requests", "large-user"))
            .contains("\"userId\":\"9007199254740993\"");
    }

    @Test
    void successfulReservationUsesRedisTimeAndLeavesAllKeysWithoutTtl() {
        store.warmUp(activeActivity(ACTIVITY_ID, 1, 1));
        long before = System.currentTimeMillis();

        PendingReservation reservation = store.reserve(ACTIVITY_ID, 2001L, "request-1").reservation();

        long after = System.currentTimeMillis();
        assertThat(reservation.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(reservation.createdAt()).isBetween(before - 1_000, after + 1_000);
        assertThat(reservation.expireAt()).isEqualTo(reservation.createdAt() + 120_000);
        assertThat(redis.opsForZSet().score(PREFIX + "pending", reservation.requestId()))
            .isEqualTo((double) reservation.expireAt());
        assertThat(List.of("stock", "requests", "users", "pending"))
            .allSatisfy(suffix -> assertThat(redis.getExpire(PREFIX + suffix)).isEqualTo(-1));
    }

    @Test
    void scriptCacheLossIsRecoveredTransparently() {
        store.warmUp(activeActivity(ACTIVITY_ID, 2, 2));
        assertThat(store.reserve(ACTIVITY_ID, 2001L, "request-1").decision())
            .isEqualTo(ReservationDecision.SUCCESS);

        redis.getConnectionFactory().getConnection().scriptingCommands().scriptFlush();

        assertThat(store.reserve(ACTIVITY_ID, 2002L, "request-2").decision())
            .isEqualTo(ReservationDecision.SUCCESS);
        assertThat(remaining(ACTIVITY_ID)).isZero();
    }

    @Test
    void wrongKeyTypeAndInvalidNumericStockFailBeforeAnyWrite() {
        store.warmUp(activeActivity(ACTIVITY_ID, 2, 2));
        redis.delete(PREFIX + "users");
        redis.opsForValue().set(PREFIX + "users", "wrong-type");

        assertThatThrownBy(() -> store.reserve(ACTIVITY_ID, 2001L, "request-1"))
            .isInstanceOf(ReservationStateException.class);
        assertThat(remaining(ACTIVITY_ID)).isEqualTo(2);
        assertThat(redis.<String, String>opsForHash().hasKey(PREFIX + "requests", "request-1"))
            .isFalse();

        redis.delete(PREFIX + "users");
        redis.opsForHash().put(PREFIX + "stock", "availableStock", "not-a-number");
        assertThatThrownBy(() -> store.reserve(ACTIVITY_ID, 2001L, "request-1"))
            .isInstanceOf(ReservationStateException.class);
        assertThat(redis.<String, String>opsForHash().hasKey(PREFIX + "requests", "request-1"))
            .isFalse();
    }

    @Test
    void nonCanonicalIntegerFailsBeforeAnyReservationWrite() {
        store.warmUp(activeActivity(ACTIVITY_ID, 2, 2));
        redis.opsForHash().put(PREFIX + "stock", "availableStock", "01");

        assertThatThrownBy(() -> store.reserve(ACTIVITY_ID, 2001L, "request-1"))
            .isInstanceOf(ReservationStateException.class);
        assertThat(redis.<String, String>opsForHash().hasKey(PREFIX + "requests", "request-1"))
            .isFalse();
        assertThat(redis.<String, String>opsForHash().hasKey(PREFIX + "users", "2001"))
            .isFalse();
        assertThat(redis.opsForZSet().score(PREFIX + "pending", "request-1")).isNull();
        assertThat(redis.<String, String>opsForHash().get(PREFIX + "stock", "availableStock"))
            .isEqualTo("01");
    }

    @Test
    void brokenRequestUserPendingAssociationFailsBeforeAnyWrite() {
        store.warmUp(activeActivity(ACTIVITY_ID, 2, 2));
        PendingReservation first = store.reserve(ACTIVITY_ID, 2001L, "request-1").reservation();
        redis.opsForZSet().remove(PREFIX + "pending", first.requestId());

        assertThatThrownBy(() -> store.reserve(ACTIVITY_ID, 2001L, "request-1"))
            .isInstanceOf(ReservationStateException.class);
        assertThat(remaining(ACTIVITY_ID)).isEqualTo(1);
        assertThat(redis.<String, String>opsForHash().hasKey(PREFIX + "requests", "request-2"))
            .isFalse();
    }

    @Test
    void lookupRejectsAReservationWhosePendingIndexIsMissing() {
        store.warmUp(activeActivity(ACTIVITY_ID, 2, 2));
        PendingReservation first = store.reserve(ACTIVITY_ID, 2001L, "request-1").reservation();
        redis.opsForZSet().remove(PREFIX + "pending", first.requestId());

        assertThatThrownBy(() -> store.findByRequestId(ACTIVITY_ID, "request-1"))
            .isInstanceOf(ReservationStateException.class);
    }

    @Test
    void orphanedPendingRequestWithMatchingCardinalityFailsBeforeAnyWrite() {
        store.warmUp(activeActivity(ACTIVITY_ID, 2, 2));
        PendingReservation first = store.reserve(ACTIVITY_ID, 2001L, "request-1").reservation();
        Double score = redis.opsForZSet().score(PREFIX + "pending", first.requestId());
        redis.opsForZSet().remove(PREFIX + "pending", first.requestId());
        redis.opsForZSet().add(PREFIX + "pending", "request-2", score);

        assertThatThrownBy(() -> store.findByRequestId(ACTIVITY_ID, "request-2"))
            .isInstanceOf(ReservationStateException.class);
        assertThatThrownBy(() -> store.reserve(ACTIVITY_ID, 2002L, "request-2"))
            .isInstanceOf(ReservationStateException.class);
        assertThat(remaining(ACTIVITY_ID)).isEqualTo(1);
        assertThat(redis.<String, String>opsForHash().hasKey(PREFIX + "requests", "request-2"))
            .isFalse();
        assertThat(redis.<String, String>opsForHash().hasKey(PREFIX + "users", "2002"))
            .isFalse();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void oneThousandConcurrentRequestsReserveExactlyOneHundredItems() throws Exception {
        store.warmUp(activeActivity(ACTIVITY_ID, 100, 100));
        int requestCount = 1000;
        int workers = 32;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReservationDecision>> futures = new ArrayList<>(requestCount);
        try {
            for (int index = 0; index < requestCount; index++) {
                long userId = 10_000L + index;
                String requestId = "request-" + index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return store.reserve(ACTIVITY_ID, userId, requestId).decision();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ReservationDecision> decisions = new ArrayList<>(requestCount);
            for (Future<ReservationDecision> future : futures) {
                decisions.add(future.get(45, TimeUnit.SECONDS));
            }
            assertThat(decisions).filteredOn(ReservationDecision.SUCCESS::equals).hasSize(100);
            assertThat(decisions).filteredOn(ReservationDecision.SOLD_OUT::equals).hasSize(900);
            assertThat(remaining(ACTIVITY_ID)).isZero();
            assertThat(redis.opsForHash().size(PREFIX + "requests")).isEqualTo(100);
            assertThat(redis.opsForZSet().size(PREFIX + "pending")).isEqualTo(100);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentRequestsFromOneUserCreateOnlyOneReservation() throws Exception {
        store.warmUp(activeActivity(ACTIVITY_ID, 10, 10));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ReservationResult> first = executor.submit(() -> reserveAfter(start, "request-1"));
            Future<ReservationResult> second = executor.submit(() -> reserveAfter(start, "request-2"));
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                .extracting(ReservationResult::decision)
                .containsExactlyInAnyOrder(ReservationDecision.SUCCESS, ReservationDecision.DUPLICATE);
            assertThat(remaining(ACTIVITY_ID)).isEqualTo(9);
            assertThat(redis.opsForHash().size(PREFIX + "requests")).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private ReservationResult reserveAfter(CountDownLatch start, String requestId)
        throws InterruptedException {
        start.await();
        return store.reserve(ACTIVITY_ID, 2001L, requestId);
    }

    private long remaining(long activityId) {
        String value = redis.<String, String>opsForHash().get(
            "fg:{activity:" + activityId + "}:stock",
            "availableStock"
        );
        return Long.parseLong(value);
    }

    private ActivityView activeActivity(long id, int initialStock, int availableStock) {
        return activity(id, initialStock, availableStock, -5, 5);
    }

    private ActivityView activity(
        long id,
        int initialStock,
        int availableStock,
        int startMinuteOffset,
        int endMinuteOffset
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new ActivityView(
            id,
            "Flash sale",
            now.plusMinutes(startMinuteOffset),
            now.plusMinutes(endMinuteOffset),
            ActivityStatus.ACTIVE,
            initialStock,
            availableStock
        );
    }
}
