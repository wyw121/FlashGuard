package com.flashguard.participation;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.*;
import com.flashguard.shared.error.BusinessException;
import com.flashguard.support.RedisContainerSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParticipationRateLimiterRedisTest extends RedisContainerSupport {
    @BeforeEach
    void clear() { redis.getConnectionFactory().getConnection().serverCommands().flushAll(); }

    private ParticipationRateLimiter limiter(int activityLimit, int userLimit, long window) {
        return new ParticipationRateLimiter(new SimpleMeterRegistry(), true, activityLimit, window, userLimit, window, redis, true);
    }

    @Test
    void twoInstancesShareBudgetsAndExpireAtRedisWindow() {
        var first = limiter(1, 1, 2000);
        var second = limiter(1, 1, 2000);
        first.ensureAllowed(1, 1);
        assertThatThrownBy(() -> second.ensureAllowed(2, 1)).isInstanceOf(BusinessException.class);
        second.ensureAllowed(2, 2);
        assertThatThrownBy(() -> second.ensureAllowed(2, 3)).isInstanceOf(BusinessException.class);
        first.ensureAllowed(3, 3);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
            .until(() -> !Boolean.TRUE.equals(redis.hasKey("fg:limit:{participation}:activity:1")));
        second.ensureAllowed(1, 1);
    }

    @Test
    void concurrentInstancesNeverExceedActivityLimit() throws Exception {
        var first = limiter(20, 1000, 60000);
        var second = limiter(20, 1000, 60000);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var requests = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 100; i++) {
                final int user = i;
                requests.add(pool.submit(() -> {
                    start.await();
                    try { (user % 2 == 0 ? first : second).ensureAllowed(1, user); return true; }
                    catch (BusinessException exception) { return false; }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var request : requests) if (request.get(10, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(20);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void reportSequentialRedisOverheadWithoutCapacityClaim() throws Exception {
        var local = new ParticipationRateLimiter(new SimpleMeterRegistry(), true, 100000, 60000, 100000, 60000);
        var global = limiter(100000, 100000, 60000);
        for (int i = 0; i < 100; i++) { local.ensureAllowed(99, 99); global.ensureAllowed(99, 99); }
        long[] localTimes = new long[500];
        long[] globalTimes = new long[500];
        for (int i = 0; i < 500; i++) {
            long start = System.nanoTime();
            local.ensureAllowed(99, 99);
            localTimes[i] = System.nanoTime() - start;
            start = System.nanoTime();
            global.ensureAllowed(99, 99);
            globalTimes[i] = System.nanoTime() - start;
        }
        Arrays.sort(localTimes);
        Arrays.sort(globalTimes);
        String report = String.format(java.util.Locale.ROOT,
            "Sequential 500 allowed calls; 100 warmup calls per mode. No capacity claim.%nlocal median_us=%.3f p95_us=%.3f%nredis median_us=%.3f p95_us=%.3f%n",
            localTimes[249] / 1000.0, localTimes[474] / 1000.0,
            globalTimes[249] / 1000.0, globalTimes[474] / 1000.0);
        java.nio.file.Path target = java.nio.file.Path.of("target", "stage6-rate-limit-overhead.txt");
        java.nio.file.Files.createDirectories(target.getParent());
        java.nio.file.Files.writeString(target, report);
        System.out.print(report);
        assertThat(redis.opsForZSet().zCard("fg:limit:{participation}:activity:99")).isEqualTo(600L);
    }
}
