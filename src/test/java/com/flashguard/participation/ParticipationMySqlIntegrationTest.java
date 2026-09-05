package com.flashguard.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import javax.sql.DataSource;

import com.flashguard.shared.error.BusinessCode;
import com.flashguard.shared.error.BusinessException;
import com.flashguard.support.MySqlContainerSupport;
import com.flashguard.inventory.ActivityStockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
class ParticipationMySqlIntegrationTest extends MySqlContainerSupport {

    private static final long ACTIVITY_ID = 1001L;

    @Autowired
    private ParticipationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @MockitoSpyBean
    private ActivityStockMapper stockMapper;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM activity_stock");
        jdbcTemplate.update("DELETE FROM activity");
    }

    @Test
    void replayCreatesOneOrderAndDeductsStockOnce() {
        seedActivity(10);
        ParticipationCommand command = new ParticipationCommand(ACTIVITY_ID, 2001L, "request-1");

        ParticipationOutcome first = service.participate(command);
        clearInvocations(stockMapper);
        ParticipationOutcome replay = service.participate(command);

        assertThat(first.code()).isEqualTo(BusinessCode.SUCCESS);
        assertThat(replay.code()).isEqualTo(BusinessCode.REPLAY);
        assertThat(replay.orderNo()).isEqualTo(first.orderNo());
        assertThat(orderCount()).isEqualTo(1);
        assertThat(availableStock()).isEqualTo(9);
        verifyNoInteractions(stockMapper);
    }

    @Test
    void duplicatePurchaseRollsBackTheSecondDeduction() {
        seedActivity(10);
        service.participate(new ParticipationCommand(ACTIVITY_ID, 2001L, "request-1"));

        assertThatThrownBy(() -> service.participate(
            new ParticipationCommand(ACTIVITY_ID, 2001L, "request-2")
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.code()).isEqualTo(BusinessCode.DUPLICATE_PURCHASE));

        assertThat(orderCount()).isEqualTo(1);
        assertThat(availableStock()).isEqualTo(9);
    }

    @Test
    void requestIdentifiersAreCaseSensitive() {
        seedActivity(10);

        ParticipationOutcome upper = service.participate(
            new ParticipationCommand(ACTIVITY_ID, 2001L, "Case-Sensitive-Request")
        );
        ParticipationOutcome lower = service.participate(
            new ParticipationCommand(ACTIVITY_ID, 2002L, "case-sensitive-request")
        );

        assertThat(upper.code()).isEqualTo(BusinessCode.SUCCESS);
        assertThat(lower.code()).isEqualTo(BusinessCode.SUCCESS);
        assertThat(orderCount()).isEqualTo(2);
        assertThat(availableStock()).isEqualTo(8);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentReplayReturnsOneOrderAndOneReplay() throws Exception {
        seedActivity(10);
        ParticipationCommand command = new ParticipationCommand(ACTIVITY_ID, 2001L, "request-1");
        clearInvocations(stockMapper);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Attempt>> futures = new ArrayList<>(2);
        List<Attempt> attempts;

        try (Connection gate = dataSource.getConnection()) {
            gate.setAutoCommit(false);
            try (PreparedStatement lock = gate.prepareStatement(
                "SELECT available_stock FROM activity_stock WHERE activity_id = ? FOR UPDATE"
            )) {
                lock.setLong(1, ACTIVITY_ID);
                lock.executeQuery();
            }

            futures.add(submitAttempt(executor, ready, start, command));
            futures.add(submitAttempt(executor, ready, start, command));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            verify(stockMapper, timeout(10_000).times(2)).decrementIfAvailable(ACTIVITY_ID);
            gate.commit();
            attempts = collectAttempts(futures);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(attempts)
            .extracting(Attempt::code)
            .containsExactlyInAnyOrder(BusinessCode.SUCCESS, BusinessCode.REPLAY);
        assertThat(attempts)
            .extracting(Attempt::orderNo)
            .doesNotContainNull()
            .containsOnly(attempts.get(0).orderNo());
        assertThat(orderCount()).isEqualTo(1);
        assertThat(availableStock()).isEqualTo(9);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentRequestsFromOneUserCreateOnlyOneOrder() throws Exception {
        seedActivity(10);

        List<Attempt> attempts = participateTogether(
            new ParticipationCommand(ACTIVITY_ID, 2001L, "request-1"),
            new ParticipationCommand(ACTIVITY_ID, 2001L, "request-2")
        );

        assertThat(attempts)
            .extracting(Attempt::code)
            .containsExactlyInAnyOrder(BusinessCode.SUCCESS, BusinessCode.DUPLICATE_PURCHASE);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(availableStock()).isEqualTo(9);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void oneThousandRequestsWithThirtyTwoWorkersNeverOversellOneHundredItems() throws Exception {
        seedActivity(100);
        int requestCount = 1000;
        int workerCount = 32;
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(workerCount);
        ExecutorService observer = Executors.newSingleThreadExecutor();
        AtomicBoolean observing = new AtomicBoolean(true);
        AtomicInteger minimumObservedStock = new AtomicInteger(availableStock());

        Future<?> observation = observer.submit(() -> {
            while (observing.get()) {
                minimumObservedStock.accumulateAndGet(availableStock(), Math::min);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            minimumObservedStock.accumulateAndGet(availableStock(), Math::min);
        });

        try {
            List<Future<BusinessCode>> results = new ArrayList<>(requestCount);
            for (int index = 0; index < requestCount; index++) {
                long userId = 10_000L + index;
                String requestId = "concurrent-request-" + index;
                results.add(workers.submit(() -> participateAfterRelease(
                    ready,
                    start,
                    userId,
                    requestId
                )));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int confirmed = 0;
            int soldOut = 0;
            for (Future<BusinessCode> result : results) {
                BusinessCode code = get(result);
                if (code == BusinessCode.SUCCESS) {
                    confirmed++;
                } else if (code == BusinessCode.SOLD_OUT) {
                    soldOut++;
                }
            }

            observing.set(false);
            observation.get(10, TimeUnit.SECONDS);

            assertThat(confirmed).isEqualTo(100);
            assertThat(soldOut).isEqualTo(900);
            assertThat(orderCount()).isEqualTo(100);
            assertThat(availableStock()).isZero();
            assertThat(minimumObservedStock.get()).isGreaterThanOrEqualTo(0);
        } finally {
            observing.set(false);
            workers.shutdownNow();
            observer.shutdownNow();
        }
    }

    private BusinessCode participateAfterRelease(
        CountDownLatch ready,
        CountDownLatch start,
        long userId,
        String requestId
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return service.participate(new ParticipationCommand(ACTIVITY_ID, userId, requestId)).code();
        } catch (BusinessException exception) {
            return exception.code();
        }
    }

    private List<Attempt> participateTogether(ParticipationCommand... commands) throws Exception {
        CountDownLatch ready = new CountDownLatch(commands.length);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(commands.length);
        try {
            List<Future<Attempt>> futures = new ArrayList<>(commands.length);
            for (ParticipationCommand command : commands) {
                futures.add(submitAttempt(executor, ready, start, command));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return collectAttempts(futures);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Future<Attempt> submitAttempt(
        ExecutorService executor,
        CountDownLatch ready,
        CountDownLatch start,
        ParticipationCommand command
    ) {
        return executor.submit(() -> {
            ready.countDown();
            start.await();
            try {
                ParticipationOutcome outcome = service.participate(command);
                return new Attempt(outcome.code(), outcome.orderNo());
            } catch (BusinessException exception) {
                return new Attempt(exception.code(), null);
            }
        });
    }

    private List<Attempt> collectAttempts(List<Future<Attempt>> futures) throws Exception {
        List<Attempt> attempts = new ArrayList<>(futures.size());
        for (Future<Attempt> future : futures) {
            attempts.add(future.get(20, TimeUnit.SECONDS));
        }
        return attempts;
    }

    private BusinessCode get(Future<BusinessCode> future) throws Exception {
        try {
            return future.get(90, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private void seedActivity(int stock) {
        jdbcTemplate.update(
            """
                INSERT INTO activity(id, name, start_time, end_time, status)
                VALUES (?, ?, UTC_TIMESTAMP(6) - INTERVAL 1 HOUR,
                        UTC_TIMESTAMP(6) + INTERVAL 1 HOUR, ?)
                """,
            ACTIVITY_ID,
            "Flash sale",
            "ACTIVE"
        );
        jdbcTemplate.update(
            """
                INSERT INTO activity_stock(activity_id, initial_stock, available_stock, version)
                VALUES (?, ?, ?, 0)
                """,
            ACTIVITY_ID,
            stock,
            stock
        );
    }

    private int orderCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
    }

    private int availableStock() {
        return jdbcTemplate.queryForObject(
            "SELECT available_stock FROM activity_stock WHERE activity_id = ?",
            Integer.class,
            ACTIVITY_ID
        );
    }

    private record Attempt(BusinessCode code, String orderNo) {
    }
}
