package com.flashguard.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

/** Controlled component experiment, deliberately not a production Cluster routing implementation. */
class RedisClusterBucketBenchmarkTest {
    private static final String IMAGE = "redis:7.4.8";
    private static final long MEMORY_BYTES = 256L * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DefaultRedisScript<List> RESERVE = script("redis/reserve_stock.lua");
    private static final DefaultRedisScript<List> WARMUP = script("redis/warmup_stock.lua");

    @Test
    @Timeout(300)
    void comparesSingleHotKeyWithThreePrimaryBucketsOnTheSameCluster() throws Exception {
        int iterations = Integer.getInteger("flashguard.benchmark.cluster.iterations", 5000);
        int workers = Integer.getInteger("flashguard.benchmark.cluster.workers", 16);
        int rounds = Integer.getInteger("flashguard.benchmark.cluster.rounds", 5);
        assertThat(iterations).isBetween(3, 100000);
        assertThat(workers).isBetween(1, 128);
        assertThat(rounds).isBetween(1, 20);
        List<Map<String, Object>> trials = new ArrayList<>();
        try (Cluster cluster = new Cluster()) {
            cluster.start();
            List<Bucket> buckets = cluster.buckets("activity:7101:bucket:", 3);
            Bucket single = cluster.buckets("activity:7101:single:", 1).get(0);
            for (int round = 1; round <= rounds; round++) {
                // Alternate execution order to reduce systematic first-run/JIT bias.
                List<String> modes = round % 2 == 1
                    ? List.of("single-hot-key", "three-primary-buckets")
                    : List.of("three-primary-buckets", "single-hot-key");
                for (String mode : modes) {
                    trials.add(measure(cluster, mode.equals("single-hot-key") ? List.of(single) : buckets,
                        mode, round, iterations, workers));
                    writeResult(cluster, trials, iterations, workers, rounds);
                }
            }
        }
        assertThat(trials).hasSize(rounds * 2);
    }

    private static Map<String, Object> measure(Cluster cluster, List<Bucket> buckets, String mode,
                                              int round, int iterations, int workers) throws Exception {
        for (Node node : cluster.nodes) {
            node.redis.execute((RedisCallback<Void>) connection -> {
                connection.serverCommands().flushAll();
                return null;
            });
        }
        // Identical warmup work on every primary for both treatments; excluded from measurements.
        for (Bucket bucket : cluster.buckets("warmup:7100:bucket:", 3)) {
            warm(bucket, 200);
            for (int i = 1; i <= 200; i++) {
                assertThat(reserve(bucket, i, "warmup-" + i).status()).isEqualTo(ReservationStatus.PENDING);
            }
        }
        int[] allocated = new int[buckets.size()];
        for (int user = 1; user <= iterations; user++) allocated[user % buckets.size()]++;
        for (int b = 0; b < buckets.size(); b++) warm(buckets.get(b), allocated[b]);

        long[] latencies = new long[iterations];
        boolean[] success = new boolean[iterations];
        var executor = Executors.newFixedThreadPool(workers);
        var ready = new CountDownLatch(workers);
        var start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<Sample> before;
        List<Sample> after;
        long elapsed;
        try {
            for (int worker = 0; worker < workers; worker++) {
                final int offset = worker;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                    for (int i = offset; i < iterations; i += workers) {
                        long began = System.nanoTime();
                        int user = i + 1;
                        PendingReservation record = reserve(buckets.get(user % buckets.size()), user, "request-" + user);
                        success[i] = record.status() == ReservationStatus.PENDING
                            && record.userId().equals(Integer.toString(user));
                        latencies[i] = System.nanoTime() - began;
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            before = cluster.samples();
            long began = System.nanoTime();
            start.countDown();
            long deadline = began + TimeUnit.SECONDS.toNanos(45);
            for (Future<?> future : futures) future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            elapsed = System.nanoTime() - began;
            after = cluster.samples();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
        int successes = 0;
        for (boolean value : success) if (value) successes++;
        List<Map<String, Object>> inventory = new ArrayList<>();
        for (int b = 0; b < buckets.size(); b++) {
            Bucket bucket = buckets.get(b);
            Object remaining = bucket.node.redis.opsForHash().get(bucket.keys.get(0), "availableStock");
            Long pending = bucket.node.redis.opsForZSet().size(bucket.keys.get(3));
            Long requests = bucket.node.redis.opsForHash().size(bucket.keys.get(1));
            Long users = bucket.node.redis.opsForHash().size(bucket.keys.get(2));
            assertThat(remaining).isEqualTo("0");
            assertThat(pending).isEqualTo((long) allocated[b]);
            assertThat(requests).isEqualTo((long) allocated[b]);
            assertThat(users).isEqualTo((long) allocated[b]);
            inventory.add(Map.of("bucket", b, "tag", bucket.tag, "slot", bucket.slot,
                "primary", bucket.node.index, "allocatedStock", allocated[b],
                "remainingStock", 0, "pending", pending, "requests", requests, "users", users));
        }
        assertThat(successes).isEqualTo(iterations);
        // Routing preserves same-user duplicate/replay checks within its bucket, outside timing.
        Bucket firstUserBucket = buckets.get(1 % buckets.size());
        assertThat(rawReserve(firstUserBucket, 1, "request-1").get(0)).isEqualTo("REPLAY");
        assertThat(rawReserve(firstUserBucket, 1, "duplicate-request-1").get(0)).isEqualTo("DUPLICATE");
        List<Map<String, Object>> cpu = new ArrayList<>();
        for (int i = 0; i < cluster.nodes.size(); i++) {
            Sample a = before.get(i);
            Sample z = after.get(i);
            double seconds = (z.nanoTime - a.nanoTime) / 1e9;
            double sys = number(z.info, "used_cpu_sys") - number(a.info, "used_cpu_sys");
            double user = number(z.info, "used_cpu_user") - number(a.info, "used_cpu_user");
            long commands = Long.parseLong(z.info.getProperty("total_commands_processed"))
                - Long.parseLong(a.info.getProperty("total_commands_processed"));
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("primary", i);
            metrics.put("sampleIntervalSeconds", seconds);
            metrics.put("cpuSysSecondsDelta", sys);
            metrics.put("cpuUserSecondsDelta", user);
            metrics.put("cpuOneCorePercent", (sys + user) / seconds * 100);
            metrics.put("totalCommandsProcessedDelta", commands);
            metrics.put("commandsPerSecondFromDelta", commands / seconds);
            metrics.put("instantaneousOpsPerSecondBefore", a.info.getProperty("instantaneous_ops_per_sec"));
            metrics.put("instantaneousOpsPerSecondAfter", z.info.getProperty("instantaneous_ops_per_sec"));
            cpu.add(metrics);
        }
        long[] sorted = latencies.clone();
        Arrays.sort(sorted);
        Map<String, Object> trial = new LinkedHashMap<>();
        trial.put("round", round);
        trial.put("mode", mode);
        trial.put("successCount", successes);
        trial.put("unexpectedDecisionCount", 0);
        trial.put("elapsedSeconds", elapsed / 1e9);
        trial.put("successfulReservationsPerSecond", successes / (elapsed / 1e9));
        trial.put("p50Millis", percentile(sorted, .50));
        trial.put("p95Millis", percentile(sorted, .95));
        trial.put("p99Millis", percentile(sorted, .99));
        trial.put("bucketInventory", inventory);
        trial.put("redisPrimaries", cpu);
        trial.put("latencyNanosByRequestIndex", latencies);
        trial.put("correctnessPassed", true);
        System.out.printf("STAGE7_CLUSTER_AB ROUND=%d MODE=%s SUCCESS=%d RPS=%.2f P99_MS=%.3f%n",
            round, mode, successes, successes / (elapsed / 1e9), percentile(sorted, .99));
        return trial;
    }

    private static void writeResult(Cluster cluster, List<Map<String, Object>> trials,
                                    int iterations, int workers, int rounds) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("recordedAtUtc", Instant.now().toString());
        report.put("experiment", "one hot inventory key vs three primary-owned inventory buckets on the SAME three-primary Cluster");
        report.put("redisImage", IMAGE);
        report.put("primaryCount", 3);
        report.put("perPrimaryCpuQuotaCores", 1);
        report.put("perPrimaryMemoryBytes", MEMORY_BYTES);
        report.put("clientOs", System.getProperty("os.name"));
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("clientAvailableProcessors", Runtime.getRuntime().availableProcessors());
        report.put("iterationsPerTrial", iterations);
        report.put("workers", workers);
        report.put("requestedRounds", rounds);
        report.put("completedTrials", trials.size());
        report.put("completed", trials.size() == rounds * 2);
        report.put("clusterNodes", cluster.topology);
        report.put("trials", trials);
        report.put("limitations", List.of(
            "Component Lua A/B only: no HTTP, DB durable reservation, MQ, order or production Cluster client acceptance.",
            "Direct mapped-port connections use verified static primary slot ownership; no failover or resharding support is tested.",
            "Same three primaries and total CPU/memory quotas in both modes; single-hot-key naturally uses one primary for writes.",
            "Deterministic user modulo bucket count; uniformly unique users, stock allocated to exact per-bucket demand.",
            "This balanced workload does not test production demand skew, bucket depletion or inventory rebalancing.",
            "CPU is Redis process sys+user divided by each node's INFO interval; 100% means one CPU core.",
            "INFO command counts include Lua internal commands, INFO and Cluster housekeeping; not reservation throughput.",
            "Client latency includes JVM, serialization and Docker/Windows network overhead; INFO snapshots are sequential.",
            "Five repetitions at one concurrency provide A/B evidence, not a saturation curve or universal scaling guarantee."
        ));
        Path output = Path.of("target", "stage7-cluster-bucket-ab.json");
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    private static void warm(Bucket bucket, int stock) {
        long now = System.currentTimeMillis();
        List<?> response = bucket.node.redis.execute(WARMUP, bucket.keys, "1", Integer.toString(stock),
            Long.toString(now - 60000), Long.toString(now + 3600000));
        assertThat(response).isNotNull();
        assertThat(response.get(0)).isEqualTo("INITIALIZED");
    }

    private static PendingReservation reserve(Bucket bucket, int user, String request) throws Exception {
        List<?> result = rawReserve(bucket, user, request);
        if (!"SUCCESS".equals(result.get(0))) throw new IllegalStateException("Unexpected Lua decision: " + result.get(0));
        return JSON.readValue(result.get(1).toString(), PendingReservation.class);
    }

    private static List<?> rawReserve(Bucket bucket, int user, String request) {
        List<?> result = bucket.node.redis.execute(RESERVE, bucket.keys, "7101", Integer.toString(user),
            request, UUID.randomUUID().toString(), "300000", "RESERVE");
        if (result == null || result.isEmpty()) throw new IllegalStateException("Missing Lua response");
        return result;
    }

    private static double number(Properties info, String key) { return Double.parseDouble(info.getProperty(key)); }
    private static double percentile(long[] sorted, double p) { return sorted[(int) Math.ceil(p * sorted.length) - 1] / 1e6; }

    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> script(String resource) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(resource));
        script.setResultType(List.class);
        return script;
    }

    private record Bucket(Node node, String tag, int slot, List<String> keys) { }
    private record Sample(Properties info, long nanoTime) { }

    private static class Node {
        final int index;
        final GenericContainer<?> container;
        LettuceConnectionFactory factory;
        StringRedisTemplate redis;
        String nodeId;
        Node(int index, Network network) {
            this.index = index;
            container = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withNetwork(network).withNetworkAliases("fg-primary-" + index).withExposedPorts(6379)
                .withCommand("redis-server", "--cluster-enabled", "yes", "--cluster-config-file", "nodes.conf",
                    "--cluster-node-timeout", "5000", "--appendonly", "no", "--save", "", "--protected-mode", "no")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                    .withNanoCPUs(1_000_000_000L).withMemory(MEMORY_BYTES));
        }
        String cli(String... arguments) throws Exception {
            String[] command = new String[arguments.length + 2];
            command[0] = "redis-cli";
            command[1] = "--raw";
            System.arraycopy(arguments, 0, command, 2, arguments.length);
            var result = container.execInContainer(command);
            if (result.getExitCode() != 0) throw new IllegalStateException(result.getStderr() + result.getStdout());
            return result.getStdout().trim();
        }
    }

    private static class Cluster implements AutoCloseable {
        final Network network = Network.newNetwork();
        final List<Node> nodes = new ArrayList<>();
        final Map<Integer, Node> owners = new LinkedHashMap<>();
        final Map<String, List<Bucket>> bucketLayouts = new LinkedHashMap<>();
        String topology;
        void start() throws Exception {
            for (int i = 0; i < 3; i++) {
                Node node = new Node(i, network);
                nodes.add(node);
                node.container.start();
            }
            String created = nodes.get(0).cli("--cluster", "create", "fg-primary-0:6379",
                "fg-primary-1:6379", "fg-primary-2:6379", "--cluster-replicas", "0", "--cluster-yes");
            assertThat(created).contains("[OK] All 16384 slots covered");
            for (Node node : nodes) {
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).until(() ->
                    node.cli("CLUSTER", "INFO").contains("cluster_state:ok"));
                node.nodeId = node.cli("CLUSTER", "MYID");
                node.factory = new LettuceConnectionFactory(node.container.getHost(), node.container.getMappedPort(6379));
                node.factory.afterPropertiesSet();
                node.redis = new StringRedisTemplate(node.factory);
                node.redis.afterPropertiesSet();
                Properties info = info(node);
                assertThat(info.getProperty("redis_version")).isEqualTo("7.4.8");
                assertThat(info.getProperty("cluster_enabled")).isEqualTo("1");
            }
            topology = nodes.get(0).cli("CLUSTER", "NODES");
            for (String line : topology.split("\\R")) {
                String[] fields = line.trim().split("\\s+");
                Node owner = nodes.stream().filter(n -> n.nodeId.equals(fields[0])).findFirst().orElseThrow();
                assertThat(fields[2]).contains("master");
                assertThat(fields[7]).isEqualTo("connected");
                for (int i = 8; i < fields.length; i++) {
                    String[] range = fields[i].split("-");
                    int low = Integer.parseInt(range[0]);
                    int high = Integer.parseInt(range[range.length - 1]);
                    for (int slot = low; slot <= high; slot++) owners.put(slot, owner);
                }
            }
            assertThat(owners).hasSize(16384);
        }
        List<Bucket> buckets(String prefix, int count) throws Exception {
            String layout = prefix + "/" + count;
            if (bucketLayouts.containsKey(layout)) return bucketLayouts.get(layout);
            List<Bucket> result = new ArrayList<>();
            for (int candidate = 0; result.size() < count && candidate < 1000; candidate++) {
                String tag = prefix + candidate;
                String keyPrefix = "fg:{" + tag + "}:";
                int slot = Integer.parseInt(nodes.get(0).cli("CLUSTER", "KEYSLOT", keyPrefix + "stock"));
                Node owner = owners.get(slot);
                if (result.stream().noneMatch(b -> b.node.index == owner.index)) {
                    result.add(new Bucket(owner, tag, slot, List.of(keyPrefix + "stock", keyPrefix + "requests",
                        keyPrefix + "users", keyPrefix + "pending", keyPrefix + "released")));
                }
            }
            assertThat(result).hasSize(count);
            bucketLayouts.put(layout, result);
            return result;
        }
        List<Sample> samples() {
            List<Sample> result = new ArrayList<>();
            for (Node node : nodes) result.add(new Sample(info(node), System.nanoTime()));
            return result;
        }
        static Properties info(Node node) {
            Properties result = node.redis.execute((RedisCallback<Properties>) connection -> connection.serverCommands().info());
            if (result == null) throw new IllegalStateException("Missing Redis INFO");
            return result;
        }
        @Override public void close() {
            for (Node node : nodes) {
                if (node.factory != null) node.factory.destroy();
                node.container.stop();
            }
            network.close();
        }
    }
}
