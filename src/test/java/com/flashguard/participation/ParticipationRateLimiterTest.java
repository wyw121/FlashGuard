package com.flashguard.participation;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import com.flashguard.shared.error.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ParticipationRateLimiterTest {
    static class MutableClock extends Clock {
        long now = 10000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }

    @Test
    void userScopeSpansActivitiesAndRejectionConsumesNeitherBudget() {
        MutableClock clock = new MutableClock();
        ParticipationRateLimiter limiter = new ParticipationRateLimiter(new SimpleMeterRegistry(), true,
            1, 1000, 1, 1000, null, false, clock);
        limiter.ensureAllowed(1, 1);
        assertThatThrownBy(() -> limiter.ensureAllowed(2, 1)).isInstanceOf(BusinessException.class);
        limiter.ensureAllowed(2, 2);
        assertThatThrownBy(() -> limiter.ensureAllowed(2, 3)).isInstanceOf(BusinessException.class);
        limiter.ensureAllowed(3, 3);
        clock.now += 1000;
        limiter.cleanup();
        limiter.ensureAllowed(1, 1);
    }

    @Test
    void globalRedisOutageRejectsAndCountsAvailabilityFailure() {
        var redis = org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"))
            .when(redis).execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(Object[].class));
        var registry = new SimpleMeterRegistry();
        var limiter = new ParticipationRateLimiter(registry, true, 10, 1000, 5, 1000, redis, true);
        assertThatThrownBy(() -> limiter.ensureAllowed(1, 1)).isInstanceOf(BusinessException.class);
        assertThat(registry.get("flashguard_rate_limit_reject_total").tag("scope", "redis_unavailable").counter().count())
            .isEqualTo(1);
    }

    @Test
    void concurrentCleanupCannotDetachAnActiveWindow() throws Exception {
        ParticipationRateLimiter limiter = new ParticipationRateLimiter(new SimpleMeterRegistry(), true, 20, 60000, 1000, 60000);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 200; i++) {
                final long user = i;
                results.add(pool.submit(() -> {
                    start.await();
                    limiter.cleanup();
                    try { limiter.ensureAllowed(1, user); return true; }
                    catch (BusinessException exception) { return false; }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(20);
        } finally { pool.shutdownNow(); }
    }
}

