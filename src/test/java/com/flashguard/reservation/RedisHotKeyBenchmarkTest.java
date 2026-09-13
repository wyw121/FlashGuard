package com.flashguard.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashguard.activity.ActivityStatus;
import com.flashguard.activity.ActivityView;
import com.flashguard.support.RedisContainerSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.core.RedisCallback;

class RedisHotKeyBenchmarkTest extends RedisContainerSupport {

    @Test
    @Timeout(60)
    void recordsSingleHotKeyLuaComponentBaseline() throws Exception {
        int iterations = Integer.getInteger("flashguard.benchmark.iterations", 5000);
        int workers = Integer.getInteger("flashguard.benchmark.workers", 16);
        assertThat(iterations).isBetween(1, 100000);
        assertThat(workers).isBetween(1, 128);
        Properties server = info("server");
        assertThat(server.getProperty("redis_version")).isEqualTo("7.4.8");
        RedisReservationStore store = new RedisReservationStore(redis, Duration.ofMinutes(5));
        // Exercise script loading, connections, decoding and UUID generation outside measurement.
        store.warmUp(activity(7000, 200));
        for (int i = 1; i <= 200; i++) {
            assertThat(store.reserve(7000, i, "warmup-" + i).decision())
                .isEqualTo(ReservationDecision.SUCCESS);
        }
        store.warmUp(activity(7001, iterations));

        long[] latencies = new long[iterations];
        ReservationDecision[] decisions = new ReservationDecision[iterations];
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(workers);
        List<Future<?>> futures = new ArrayList<>();
        Properties before;
        Properties after;
        long elapsedNanos;
        long infoIntervalNanos;
        try {
            // Exactly one task per worker: no queued task waits on a barrier it cannot reach.
            for (int worker = 0; worker < workers; worker++) {
                int offset = worker;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Benchmark start latch timed out");
                    }
                    for (int i = offset; i < iterations; i += workers) {
                        long began = System.nanoTime();
                        decisions[i] = store.reserve(7001, i + 1L, "measured-" + i).decision();
                        latencies[i] = System.nanoTime() - began;
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            before = info("all");
            long infoStart = System.nanoTime();
            long began = System.nanoTime();
            start.countDown();
            long deadline = began + TimeUnit.SECONDS.toNanos(45);
            for (Future<?> future : futures) {
                future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
            elapsedNanos = System.nanoTime() - began;
            after = info("all");
            infoIntervalNanos = System.nanoTime() - infoStart;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        long successes = Arrays.stream(decisions).filter(d -> d == ReservationDecision.SUCCESS).count();
        Long remaining = store.getAvailableStock(7001);
        long pending = store.countPendingReservations(7001);
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double infoSeconds = infoIntervalNanos / 1_000_000_000.0;
        double cpuSys = delta(before, after, "used_cpu_sys");
        double cpuUser = delta(before, after, "used_cpu_user");
        long commandDelta = Long.parseLong(after.getProperty("total_commands_processed"))
            - Long.parseLong(before.getProperty("total_commands_processed"));
        long[] sorted = latencies.clone();
        Arrays.sort(sorted);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordedAtUtc", Instant.now().toString());
        result.put("scope", "single activity hot stock key, production multi-key Lua on one Redis node");
        result.put("redisImage", REDIS.getDockerImageName());
        result.put("redisVersion", server.getProperty("redis_version"));
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("clientOs", System.getProperty("os.name"));
        result.put("clientAvailableProcessors", Runtime.getRuntime().availableProcessors());
        result.put("workers", workers);
        result.put("iterations", iterations);
        result.put("warmupReservations", 200);
        result.put("successCount", successes);
        result.put("unexpectedDecisionCount", iterations - successes);
        result.put("remainingStock", remaining);
        result.put("pendingReservations", pending);
        result.put("elapsedSeconds", elapsedSeconds);
        result.put("successfulReservationsPerSecond", successes / elapsedSeconds);
        result.put("p50Millis", percentileMillis(sorted, 0.50));
        result.put("p95Millis", percentileMillis(sorted, 0.95));
        result.put("p99Millis", percentileMillis(sorted, 0.99));
        result.put("redisInfoIntervalSeconds", infoSeconds);
        result.put("redisCpuSysSecondsDelta", cpuSys);
        result.put("redisCpuUserSecondsDelta", cpuUser);
        result.put("redisCpuOneCorePercent", 100.0 * (cpuSys + cpuUser) / infoSeconds);
        result.put("redisTotalCommandsProcessedDelta", commandDelta);
        result.put("redisCommandsPerSecondFromDelta", commandDelta / infoSeconds);
        result.put("redisInstantaneousOpsPerSecondBefore", before.getProperty("instantaneous_ops_per_sec"));
        result.put("redisInstantaneousOpsPerSecondAfter", after.getProperty("instantaneous_ops_per_sec"));
        result.put("latencyNanosByRequestIndex", latencies);
        result.put("limitations", List.of(
            "Client-observed latency includes JVM, serialization, network and Docker/Windows overhead.",
            "Redis INFO command counts include Lua internal commands and INFO sampling, not business requests.",
            "CPU is Redis process sys+user time divided by INFO interval; 100% equals one core, not host CPU.",
            "INFO boundaries approximate the workload interval; instantaneous ops/s is Redis's rolling sample.",
            "One closed-loop component run does not establish saturation, HTTP capacity, durable orders or MQ capacity.",
            "No Redis Cluster bucket A/B has been performed by this test."
        ));
        result.put("correctnessPassed", successes == iterations && Long.valueOf(0).equals(remaining)
            && pending == iterations);
        Path output = Path.of("target", "stage7-hot-key-baseline.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
        System.out.printf("STAGE7_BASELINE=%s SUCCESS=%d RPS=%.2f P99_MS=%.3f REDIS_CPU_ONE_CORE_PCT=%.2f%n",
            output.toAbsolutePath(), successes, successes / elapsedSeconds,
            percentileMillis(sorted, 0.99), 100.0 * (cpuSys + cpuUser) / infoSeconds);
        assertThat(decisions).containsOnly(ReservationDecision.SUCCESS);
        assertThat(remaining).isZero();
        assertThat(pending).isEqualTo(iterations);
    }

    private static ActivityView activity(long id, int stock) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new ActivityView(id, "stage7-hot-key-baseline", now.minusMinutes(1),
            now.plusHours(1), ActivityStatus.ACTIVE, stock, stock);
    }

    private static Properties info(String section) {
        Properties properties = redis.execute((RedisCallback<Properties>) connection ->
            connection.serverCommands().info(section));
        if (properties == null) throw new IllegalStateException("Redis INFO returned no metrics");
        return properties;
    }

    private static double delta(Properties before, Properties after, String key) {
        return Double.parseDouble(after.getProperty(key)) - Double.parseDouble(before.getProperty(key));
    }

    private static double percentileMillis(long[] sorted, double percentile) {
        return sorted[(int) Math.ceil(sorted.length * percentile) - 1] / 1_000_000.0;
    }
}
