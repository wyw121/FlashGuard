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
        service = new ParticipationService(transaction, orderMapper);
    }

    @Test
    void replayReturnsExistingOrderWithoutDeductingStock() {
        OrderRecord existing = confirmedOrder("order-existing", "request-1", 2001L);
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.of(existing));

        ParticipationOutcome outcome = transactionUnderTest.execute(COMMAND);

        assertThat(outcome.code()).isEqualTo(BusinessCode.REPLAY);
        assertThat(outcome.orderNo()).isEqualTo("order-existing");
        verify(stockMapper, never()).decrementIfAvailable(1001L);
        verify(orderMapper, never()).insert(org.mockito.ArgumentMatchers.any());
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
        verify(stockMapper, never()).decrementIfAvailable(1001L);
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
        verify(stockMapper, never()).decrementIfAvailable(1001L);
    }

    @Test
    void zeroAffectedRowsMeansSoldOut() {
        stubActiveActivity();
        when(stockMapper.decrementIfAvailable(1001L)).thenReturn(0);

        assertBusinessCode(
            () -> transactionUnderTest.execute(COMMAND),
            BusinessCode.SOLD_OUT
        );
        verify(orderMapper, never()).insert(org.mockito.ArgumentMatchers.any());
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
        when(transaction.execute(COMMAND)).thenThrow(new DuplicateKeyException("duplicate"));
        when(orderMapper.findByRequestId("request-1"))
            .thenReturn(Optional.of(confirmedOrder("order-existing", "request-1", 2001L)));

        ParticipationOutcome outcome = service.participate(COMMAND);

        assertThat(outcome.code()).isEqualTo(BusinessCode.REPLAY);
        assertThat(outcome.orderNo()).isEqualTo("order-existing");
        verify(orderMapper, never()).findByActivityAndUser(1001L, 2001L);
    }

    @Test
    void duplicatePurchaseAfterRollbackUsesStableBusinessCode() {
        when(transaction.execute(COMMAND)).thenThrow(new DuplicateKeyException("duplicate"));
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.empty());
        when(orderMapper.findByActivityAndUser(1001L, 2001L))
            .thenReturn(Optional.of(confirmedOrder("other-order", "other-request", 2001L)));

        assertBusinessCode(
            () -> service.participate(COMMAND),
            BusinessCode.DUPLICATE_PURCHASE
        );
    }

    private void stubActiveActivity() {
        when(orderMapper.findByRequestId("request-1")).thenReturn(Optional.empty());
        when(activityMapper.findById(1001L)).thenReturn(Optional.of(activity(
            LocalDateTime.of(2026, 9, 5, 3, 0),
            LocalDateTime.of(2026, 9, 5, 5, 0),
            ActivityStatus.ACTIVE
        )));
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

    private void assertBusinessCode(Runnable action, BusinessCode expected) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.code()).isEqualTo(expected));
    }
}
