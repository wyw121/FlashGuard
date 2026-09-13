package com.flashguard.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.flashguard.activity.ActivityMapper;
import com.flashguard.activity.ActivityStatus;
import com.flashguard.activity.ActivityView;
import com.flashguard.inventory.ActivityStockMapper;
import com.flashguard.order.OrderMapper;
import com.flashguard.order.OrderRecord;
import com.flashguard.order.OrderStatus;
import com.flashguard.reservation.PendingReservation;
import com.flashguard.reservation.ReservationDecision;
import com.flashguard.reservation.ReservationResult;
import com.flashguard.reservation.RedisReservationStore;
import com.flashguard.reservation.StockReservationRecord;
import com.flashguard.reservation.StockReservationStatus;
import com.flashguard.reservation.StockReservationService;
import com.flashguard.shared.error.BusinessCode;
import com.flashguard.shared.error.BusinessException;
import com.flashguard.shared.id.OrderNumberGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class ParticipationServiceTest {

    private static final Clock NOW = Clock.fixed(
        Instant.parse("2026-09-05T04:00:00Z"),
        ZoneOffset.UTC
    );
    private static final ParticipationCommand COMMAND =
        new ParticipationCommand(1001L, 2001L, "request-1");

    @Mock
    private ActivityMapper activityMapper;

    @Mock
    private ActivityStockMapper stockMapper;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderNumberGenerator orderNumberGenerator;

    @Mock
    private ParticipationTransaction transaction;

    @Mock
    private RedisReservationStore reservationStore;

    @Mock
    private StockReservationService stockReservationService;

    @Mock private com.flashguard.mq.ReservationMessagePublisher publisher;
    private ParticipationTransaction transactionUnderTest;
    private ParticipationService service;

    @BeforeEach
    void setUp() {
        transactionUnderTest = new ParticipationTransaction(
            activityMapper,
            stockMapper,
            orderMapper,
            orderNumberGenerator,
            NOW
        );
        service = new ParticipationService(
            transaction,
            orderMapper,
            reservationStore,
            stockReservationService,
            org.mockito.Mockito.mock(ParticipationRateLimiter.class), publisher, "async"
        );
    }

    @Test
    void pendingReservationIsPersistedAndReturned() {
        PendingReservation pending = reservation("SUCCESS", 1_693_123_456_000L, 1_693_123_576_000L);
        StockReservationRecord persisted = persistedReservation(pending);
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.empty());
        when(reservationStore.reserve(1001L, 2001L, "request-1"))
            .thenReturn(new ReservationResult(ReservationDecision.SUCCESS, pending));
        when(publisher.publish(pending)).thenReturn(persisted);

        ParticipationOutcome outcome = service.participate(COMMAND);

        assertThat(outcome.code()).isEqualTo(BusinessCode.PENDING);
        assertThat(outcome.requestId()).isEqualTo("request-1");
        assertThat(outcome.orderNo()).isNull();
        assertThat(outcome.status()).isEqualTo("PENDING");
        verify(reservationStore).reserve(1001L, 2001L, "request-1");
        verify(publisher).publish(pending);
    }

    @Test
    void replayReservationReturnsPendingUsingExistingStockReservation() {
        PendingReservation pending = reservation("SUCCESS", 1_693_123_456_000L, 1_693_123_576_000L);
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.empty());
        when(reservationStore.reserve(1001L, 2001L, "request-1"))
            .thenReturn(new ReservationResult(ReservationDecision.REPLAY, pending));
        when(publisher.publish(pending)).thenReturn(persistedReservation(pending));

        ParticipationOutcome outcome = service.participate(COMMAND);

        assertThat(outcome.code()).isEqualTo(BusinessCode.PENDING);
        assertThat(outcome.status()).isEqualTo("PENDING");
        verify(publisher).publish(pending);
        verify(stockReservationService, never()).createPending(pending);
    }

    @Test
    void duplicateReservationDecisionMapsToDuplicatePurchase() {
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.empty());
        when(reservationStore.reserve(1001L, 2001L, "request-1"))
            .thenReturn(ReservationResult.withoutReservation(ReservationDecision.DUPLICATE));

        assertBusinessCode(
            () -> service.participate(COMMAND),
            BusinessCode.DUPLICATE_PURCHASE
        );

        verify(stockReservationService).findByRequestId("request-1");
        verify(transaction, never()).execute(COMMAND);
    }

    @Test
    void notReadyFailsClosedWithoutLegacyFallback() {
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.empty());
        when(reservationStore.reserve(1001L, 2001L, "request-1"))
            .thenReturn(ReservationResult.withoutReservation(ReservationDecision.NOT_READY));
        assertBusinessCode(() -> service.participate(COMMAND), BusinessCode.SERVICE_UNAVAILABLE);
        verify(transaction, never()).execute(COMMAND);
    }
    @Test
    void redisFailureFailsClosedWithoutLegacyFallback() {
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.empty());
        when(reservationStore.reserve(1001L, 2001L, "request-1"))
            .thenThrow(new com.flashguard.reservation.ReservationStoreException("unavailable"));
        assertBusinessCode(() -> service.participate(COMMAND), BusinessCode.SERVICE_UNAVAILABLE);
        verify(transaction, never()).execute(COMMAND);
    }

    @Test
    void replayReturnsExistingOrderWithoutDeductingStock() {
        OrderRecord existing = confirmedOrder("order-existing", "request-1", 2001L);
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.of(existing));

        ParticipationOutcome outcome = transactionUnderTest.execute(COMMAND);

        assertThat(outcome.code()).isEqualTo(BusinessCode.REPLAY);
        assertThat(outcome.orderNo()).isEqualTo("order-existing");
    }

    @Test
    void missingActivityUsesStableBusinessCode() {
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.empty());
        when(activityMapper.findById(1001L)).thenReturn(Optional.empty());

        assertBusinessCode(
            () -> transactionUnderTest.execute(COMMAND),
            BusinessCode.ACTIVITY_NOT_FOUND
        );
    }

    @Test
    void activityBeforeStartIsRejected() {
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.empty());
        when(activityMapper.findById(1001L)).thenReturn(Optional.of(activity(
            LocalDateTime.of(2026, 9, 5, 5, 0),
            LocalDateTime.of(2026, 9, 5, 6, 0),
            ActivityStatus.ACTIVE
        )));

        assertBusinessCode(
            () -> transactionUnderTest.execute(COMMAND),
            BusinessCode.ACTIVITY_NOT_STARTED
        );
    }

    @Test
    void endedActivityIsRejected() {
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.empty());
        when(activityMapper.findById(1001L)).thenReturn(Optional.of(activity(
            LocalDateTime.of(2026, 9, 5, 2, 0),
            LocalDateTime.of(2026, 9, 5, 3, 0),
            ActivityStatus.ACTIVE
        )));

        assertBusinessCode(
            () -> transactionUnderTest.execute(COMMAND),
            BusinessCode.ACTIVITY_ENDED
        );
    }

    @Test
    void zeroAffectedRowsMeansSoldOut() {
        stubActiveActivity();
        when(stockMapper.decrementIfAvailable(1001L)).thenReturn(0);

        assertBusinessCode(
            () -> transactionUnderTest.execute(COMMAND),
            BusinessCode.SOLD_OUT
        );
    }

    @Test
    void successfulParticipationDeductsOnceAndInsertsConfirmedOrder() {
        stubActiveActivity();
        when(stockMapper.decrementIfAvailable(1001L)).thenReturn(1);
        when(orderNumberGenerator.next()).thenReturn("01K4C1YJ000000000000000001");

        ParticipationOutcome outcome = transactionUnderTest.execute(COMMAND);

        assertThat(outcome).isEqualTo(new ParticipationOutcome(
            BusinessCode.SUCCESS,
            "request-1",
            "01K4C1YJ000000000000000001",
            "CONFIRMED"
        ));
        verify(stockMapper).decrementIfAvailable(1001L);
        ArgumentCaptor<OrderRecord> order = ArgumentCaptor.forClass(OrderRecord.class);
        verify(orderMapper).insert(order.capture());
        assertThat(order.getValue().activityId()).isEqualTo(1001L);
        assertThat(order.getValue().userId()).isEqualTo(2001L);
        assertThat(order.getValue().status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void duplicateRequestAfterRollbackIsMappedToReplay() {
        service = new ParticipationService(transaction, orderMapper, reservationStore, stockReservationService, org.mockito.Mockito.mock(ParticipationRateLimiter.class));
        when(transaction.execute(COMMAND)).thenThrow(new DuplicateKeyException("duplicate"));
        when(orderMapper.findByRequestId("request-1"))
            .thenReturn(Optional.empty(), Optional.of(confirmedOrder("order-existing", "request-1", 2001L)));

        ParticipationOutcome outcome = service.participate(COMMAND);

        assertThat(outcome.code()).isEqualTo(BusinessCode.REPLAY);
        assertThat(outcome.orderNo()).isEqualTo("order-existing");
        verify(orderMapper, never()).findByActivityAndUser(1001L, 2001L);
    }

    @Test
    void duplicatePurchaseAfterRollbackUsesStableBusinessCode() {
        service = new ParticipationService(transaction, orderMapper, reservationStore, stockReservationService, org.mockito.Mockito.mock(ParticipationRateLimiter.class));
        when(transaction.execute(COMMAND)).thenThrow(new DuplicateKeyException("duplicate"));
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.empty());
        when(orderMapper.findByActivityAndUser(1001L, 2001L))
            .thenReturn(Optional.of(confirmedOrder("other-order", "other-request", 2001L)));

        assertBusinessCode(
            () -> service.participate(COMMAND),
            BusinessCode.DUPLICATE_PURCHASE
        );
    }

    @Test
    void findByRequestIdFallsBackToStockReservationWhenOrderIsMissing() {
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.empty());
        when(stockReservationService.findByRequestId("request-1"))
            .thenReturn(Optional.of(persistedReservation(reservation("SUCCESS", 1_693_123_456_000L, 1_693_123_556_000L))));

        ParticipationOutcome outcome = service.findByRequestId("request-1");

        assertThat(outcome.code()).isEqualTo(BusinessCode.PENDING);
        assertThat(outcome.requestId()).isEqualTo("request-1");
        assertThat(outcome.status()).isEqualTo("PENDING");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1002,2001", "1001,2002"})
    void orderRequestIdentityConflictIsRejectedBeforeRedis(long activity, long user) {
        var existing = confirmedOrder("order-existing", "request-1", 2001L);
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.of(existing));
        assertBusinessCode(() -> service.participate(new ParticipationCommand(activity,user,"request-1")),
            BusinessCode.DUPLICATE_PURCHASE);
        org.mockito.Mockito.verifyNoInteractions(reservationStore, publisher, transaction);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1002,2001", "1001,2002"})
    void durableRequestIdentityConflictIsRejectedBeforeRedis(long activity, long user) {
        when(stockReservationService.findByRequestId("request-1"))
            .thenReturn(Optional.of(persistedReservation(reservation("SUCCESS", 0, 60000))));
        assertBusinessCode(() -> service.participate(new ParticipationCommand(activity,user,"request-1")),
            BusinessCode.DUPLICATE_PURCHASE);
        org.mockito.Mockito.verifyNoInteractions(reservationStore, publisher, transaction);
    }

    @Test
    void matchingDurableRequestStillUsesPublisherAfterRedisReplay() {
        var pending = reservation("SUCCESS", 0, 60000);
        var row = persistedReservation(pending);
        when(stockReservationService.findByRequestId("request-1")).thenReturn(Optional.of(row));
        when(reservationStore.reserve(1001L,2001L,"request-1"))
            .thenReturn(new ReservationResult(ReservationDecision.REPLAY,pending));
        when(publisher.publish(pending)).thenReturn(row);
        assertThat(service.participate(COMMAND).code()).isEqualTo(BusinessCode.PENDING);
        verify(publisher).publish(pending);
    }
    @Test
    void concurrentConflictingOrderIsNotReturnedFromLegacyDuplicateRecovery() {
        service = new ParticipationService(transaction, orderMapper, reservationStore, stockReservationService,
            org.mockito.Mockito.mock(ParticipationRateLimiter.class));
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.empty(),
            Optional.of(confirmedOrder("other-order", "request-1", 9999L)));
        when(transaction.execute(COMMAND)).thenThrow(new DuplicateKeyException("concurrent insert"));
        assertBusinessCode(() -> service.participate(COMMAND), BusinessCode.DUPLICATE_PURCHASE);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1002,2001", "1001,2002"})
    void legacyTransactionRejectsConcurrentConflictingReplay(long activity,long user) {
        when(orderMapper.findByRequestId("request-1"))
            .thenReturn(Optional.of(confirmedOrder("other-order", "request-1", 2001L)));
        assertBusinessCode(() -> transactionUnderTest.execute(new ParticipationCommand(activity,user,"request-1")),
            BusinessCode.DUPLICATE_PURCHASE);
        org.mockito.Mockito.verifyNoInteractions(stockMapper, activityMapper, orderNumberGenerator);
    }

    private void stubActiveActivity() {
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.empty());
        when(activityMapper.findById(1001L)).thenReturn(Optional.of(activity(
            LocalDateTime.of(2026, 9, 5, 3, 0),
            LocalDateTime.of(2026, 9, 5, 5, 0),
            ActivityStatus.ACTIVE
        )));
    }

    @Test
    void fastConsumerConfirmationReturnsCompleteOrderFromPost() {
        var pending = reservation("SUCCESS", 0, 60000);
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.empty(),
            Optional.of(confirmedOrder("fast-order", "request-1", 2001L)));
        when(reservationStore.reserve(1001L, 2001L, "request-1"))
            .thenReturn(new ReservationResult(ReservationDecision.SUCCESS, pending));
        when(publisher.publish(pending)).thenReturn(confirmedReservation());

        assertThat(service.participate(COMMAND)).isEqualTo(new ParticipationOutcome(
            BusinessCode.SUCCESS, "request-1", "fast-order", "CONFIRMED"));
    }

    @Test
    void resultReadRereadsOrderWhenConsumerCommitsBetweenQueries() {
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.empty(),
            Optional.of(confirmedOrder("fast-order", "request-1", 2001L)));
        when(stockReservationService.findByRequestId("request-1"))
            .thenReturn(Optional.of(confirmedReservation()));

        assertThat(service.findByRequestId("request-1")).isEqualTo(new ParticipationOutcome(
            BusinessCode.SUCCESS, "request-1", "fast-order", "CONFIRMED"));
    }

    @Test
    void confirmedReservationWithoutOrderFailsInsteadOfReturningIncompleteSuccess() {
        when(stockReservationService.findByRequestId("request-1"))
            .thenReturn(Optional.of(confirmedReservation()));
        assertBusinessCode(() -> service.findByRequestId("request-1"), BusinessCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void confirmedReservationDoesNotReturnAnotherUsersOrder() {
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.empty(),
            Optional.of(confirmedOrder("wrong-order", "request-1", 9999L)));
        when(stockReservationService.findByRequestId("request-1"))
            .thenReturn(Optional.of(confirmedReservation()));
        assertBusinessCode(() -> service.findByRequestId("request-1"), BusinessCode.SERVICE_UNAVAILABLE);
    }

    private StockReservationRecord confirmedReservation() {
        var row = persistedReservation(reservation("SUCCESS", 0, 60000));
        return new StockReservationRecord(row.id(), row.reservationId(), row.activityId(), row.userId(),
            row.requestId(), StockReservationStatus.CONFIRMED, row.reservationId(), row.expireAt(),
            row.createdAt(), row.updatedAt());
    }

    private ActivityView activity(
        LocalDateTime start,
        LocalDateTime end,
        ActivityStatus status
    ) {
        return new ActivityView(1001L, "Flash sale", start, end, status, 10, 10);
    }

    private OrderRecord confirmedOrder(String orderNo, String requestId, long userId) {
        LocalDateTime createdAt = LocalDateTime.ofInstant(NOW.instant(), ZoneOffset.UTC);
        return new OrderRecord(
            1L,
            orderNo,
            requestId,
            1001L,
            userId,
            OrderStatus.CONFIRMED,
            createdAt,
            createdAt
        );
    }

    private PendingReservation reservation(String activitySuffix, long createdAt, long expireAt) {
        return new PendingReservation(
            "res-" + activitySuffix,
            "1001",
            "2001",
            "request-1",
            com.flashguard.reservation.ReservationStatus.PENDING,
            createdAt,
            expireAt
        );
    }

    private StockReservationRecord persistedReservation(PendingReservation reservation) {
        return new StockReservationRecord(
            1L,
            reservation.reservationId(),
            1001L,
            2001L,
            reservation.requestId(),
            StockReservationStatus.PENDING,
            null,
            LocalDateTime.ofInstant(Instant.ofEpochMilli(reservation.expireAt()), ZoneOffset.UTC),
            LocalDateTime.ofInstant(NOW.instant(), ZoneOffset.UTC),
            LocalDateTime.ofInstant(NOW.instant(), ZoneOffset.UTC)
        );
    }

    private void assertBusinessCode(Runnable action, BusinessCode expected) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.code()).isEqualTo(expected));
    }
}
