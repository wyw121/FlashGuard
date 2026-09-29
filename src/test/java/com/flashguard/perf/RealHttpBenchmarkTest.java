package com.flashguard.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashguard.FlashGuardApplication;
import com.flashguard.activity.ActivityMapper;
import com.flashguard.reservation.RedisReservationStore;
import com.flashguard.support.MySqlContainerSupport;
import com.flashguard.support.RocketMqProcessSupport;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.net.URI;
import java.net.http.*;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Opt-in real HTTP benchmark. PENDING is never counted as a completed order. */
@EnabledIfSystemProperty(named = "flashguard.perf.enabled", matches = "true")
class RealHttpBenchmarkTest extends MySqlContainerSupport {
    final ObjectMapper json = new ObjectMapper();
    final int repetitions = Integer.getInteger("perf.repetitions", 5);
    final int seconds = Integer.getInteger("perf.seconds", 180);
    final int warmupSeconds = Integer.getInteger("perf.warmup.seconds", 60);
    final int rate = Integer.getInteger("perf.rate", 10);
    final int vus = Integer.getInteger("perf.vus", 100);
    final List<Map<String, Object>> results = new ArrayList<>();
    final List<Map<String, Object>> warmups = new ArrayList<>();
    boolean suiteCompleted;
    final String k6 = System.getProperty("perf.k6", Files.exists(Path.of("C:/Program Files/k6/k6.exe"))
        ? "C:/Program Files/k6/k6.exe" : "k6");
    final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4.8"))
        .withExposedPorts(6379).withCommand("redis-server", "--appendonly", "no", "--save", "");
    Path output;
    RocketMqProcessSupport broker;
    DefaultMQAdminExt admin;
    String topic;
    String consumerGroup;
    int activitySequence = 7000;

    @Test void repeatableActualHttpComparison() throws Exception {
        if (MYSQL == null) throw new IllegalStateException("Benchmark refuses external DB: only test-owned resources are allowed");
        assertThat(repetitions).isPositive(); assertThat(seconds).isPositive(); assertThat(rate).isPositive(); assertThat(vus).isPositive();
        String sha = command("git", "rev-parse", "HEAD").trim();
        output = Files.createDirectories(Path.of("benchmark", "results", sha,
            Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID().toString().substring(0, 8))).toAbsolutePath();
        broker = new RocketMqProcessSupport(6000, 5000);
        try {
            MYSQL.start(); redis.start(); broker.start();
            admin = new DefaultMQAdminExt(); admin.setNamesrvAddr(broker.nameserverAddress());
            admin.setInstanceName("perf-admin-" + UUID.randomUUID()); admin.setVipChannelEnabled(false); admin.start();
            writeEnvironment(sha);
            Set<String> selected = new LinkedHashSet<>(Arrays.asList(System.getProperty("perf.scenarios",
                "mysql-success,mysql-soldout,mysql-replay,mysql-mixed,async-success,async-soldout,async-replay,async-mixed,async-limiter-local,async-limiter-global").split(",")));
            Set<String> known = Set.of("mysql-success", "mysql-soldout", "mysql-replay", "mysql-mixed", "async-success", "async-soldout", "async-replay", "async-mixed", "async-limiter-local", "async-limiter-global");
            if (!known.containsAll(selected)) throw new IllegalArgumentException("Unknown perf.scenarios: " + selected);
            for (String profile : List.of("mysql", "async", "async-limiter-local", "async-limiter-global")) {
                List<String> scenarios = selected.stream().filter(s -> profile.startsWith("async-limiter") ? s.equals(profile)
                    : s.startsWith(profile + "-") && !s.startsWith("async-limiter")).toList();
                if (scenarios.isEmpty()) continue;
                String suffix = UUID.randomUUID().toString().replace("-", "");
                topic = "fg_perf_" + suffix; consumerGroup = "fg_perf_c_" + suffix;
                admin.createAndUpdateTopicConfig(broker.brokerAddress(), new TopicConfig(topic, 1, 1, 6));
                await().atMost(Duration.ofSeconds(45)).ignoreExceptions().until(() -> !admin.examineTopicRouteInfo(topic).getQueueDatas().isEmpty());
                try (var app = application(profile, suffix)) {
                    for (String scenario : scenarios) {
                        if (warmupSeconds > 0) run(app, scenario, 0, true);
                        for (int run = 1; run <= repetitions; run++) run(app, scenario, run, false);
                    }
                }
            }
            suiteCompleted = true;
            writeAggregate();
            assertThat(results).allSatisfy(result -> assertThat(result.get("technicalStatus")).isEqualTo("PASS"));
        } finally {
            if (output != null) writeAggregate();
            if (admin != null) admin.shutdown();
            if (broker != null) broker.close();
            redis.stop();
            // MySqlContainerSupport owns the shared JVM container; Ryuk stops it on JVM exit.
        }
    }

    private ServletWebServerApplicationContext application(String profile, String suffix) {
        boolean async = !profile.equals("mysql");
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(FlashGuardApplication.class)
            .web(WebApplicationType.SERVLET).run(
                "--server.port=0", "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.username=" + MYSQL.getUsername(), "--spring.datasource.password=" + MYSQL.getPassword(),
                "--spring.datasource.hikari.maximum-pool-size=20", "--spring.data.redis.host=" + redis.getHost(),
                "--spring.data.redis.port=" + redis.getMappedPort(6379), "--flashguard.scheduling.enabled=false",
                "--flashguard.participation.mode=" + (async ? "async" : "mysql"), "--flashguard.mq.enabled=" + async,
                "--flashguard.mq.nameserver=" + broker.nameserverAddress(), "--flashguard.mq.topic=" + topic,
                "--flashguard.mq.producer-group=fg_perf_p_" + suffix, "--flashguard.mq.consumer-group=" + consumerGroup,
                "--flashguard.mq.consumer-enabled=true", "--flashguard.mq.consume-thread-min=4", "--flashguard.mq.consume-thread-max=8",
                "--flashguard.mq.retry-delay-level=1", "--flashguard.mq.max-reconsume-times=3",
                "--flashguard.reservation.timeout=300s", "--flashguard.ratelimit.enabled=" + profile.contains("limiter"),
                "--flashguard.ratelimit.global-enabled=" + profile.endsWith("global"),
                "--flashguard.ratelimit.activity.limit=" + Math.max(1, rate / 2), "--flashguard.ratelimit.user.limit=10000",
                "--logging.level.com.flashguard=WARN", "--spring.main.banner-mode=off");
    }

    private void run(ServletWebServerApplicationContext app, String scenario, int repetition, boolean warmup) throws Exception {
        int runSeconds = warmup ? warmupSeconds : seconds;
        Path directory = Files.createDirectories(output.resolve(scenario).resolve(warmup ? "warmup-excluded" : String.format("run-%02d", repetition)));
        JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
        RedisReservationStore store = app.getBean(RedisReservationStore.class);
        StringRedisTemplate template = app.getBean(StringRedisTemplate.class);
        long activity = ++activitySequence;
        String kind = scenario.contains("limiter") ? "limiter" : scenario.substring(scenario.indexOf('-') + 1);
        boolean async = scenario.startsWith("async");
        int stock = kind.equals("soldout") ? Math.max(1, rate / 5) : rate * runSeconds + 100;
        // Unique activity/user/request namespace plus zero broker lag prevents cross-run state reuse.
        jdbc.update("INSERT INTO activity(id,name,start_time,end_time,status) VALUES (?,'http-benchmark',UTC_TIMESTAMP()-INTERVAL 1 HOUR,UTC_TIMESTAMP()+INTERVAL 1 HOUR,'ACTIVE')", activity);
        jdbc.update("INSERT INTO activity_stock(activity_id,initial_stock,available_stock) VALUES (?,?,?)", activity, stock, stock);
        if (async) store.warmUp(app.getBean(ActivityMapper.class).findById(activity).orElseThrow());
        String baseUrl = "http://127.0.0.1:" + app.getWebServer().getPort();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("scenario", scenario); record.put("repetition", repetition); record.put("activityId", activity);
        record.put("initialStock", stock); record.put("targetRps", rate); record.put("seconds", runSeconds); record.put("vus", vus);
        record.put("warmupExcluded", warmup); record.put("protocolShortfalls", protocolShortfalls());
        record.put("topic", topic); record.put("consumerGroup", consumerGroup); record.put("start", Instant.now().toString());
        record.put("dataSeed", "activity=" + activity + (kind.equals("mixed")
            ? ";users=1000000+floor(iteration/2);request=runId+iteration;expectedDuplicateFraction=0.5"
            : ";users=1000000+slot;slot=replay?iteration%50:iteration"));
        record.put("mysqlSettings", jdbc.queryForMap("SELECT @@version AS version, @@innodb_buffer_pool_size AS bufferPoolBytes, @@max_connections AS maxConnections, @@transaction_isolation AS transactionIsolation, @@innodb_flush_log_at_trx_commit AS flushLogAtCommit"));
        long messagesBefore = brokerMessages(); record.put("brokerMessagesBefore", messagesBefore);
        write(directory.resolve("config.json"), record);
        Files.writeString(directory.resolve("docker-before.json"), command("docker", "stats", "--no-stream", "--format", "{{json .}}", MYSQL.getContainerId(), redis.getContainerId()));
        capturePrometheus(baseUrl, directory.resolve("prometheus-before.txt"));
        long start = System.nanoTime();
        ProcessBuilder process = new ProcessBuilder(k6, "run", "--out", "json=" + directory.resolve("raw.json"),
            "--summary-export", directory.resolve("summary.json").toString(), Path.of("benchmark/k6/http-full-path.js").toAbsolutePath().toString());
        Map<String, String> env = process.environment();
        env.put("BASE_URL", baseUrl); env.put("ACTIVITY_ID", Long.toString(activity)); env.put("SCENARIO", kind);
        env.put("RUN_ID", "b" + activity + "r" + repetition); env.put("RATE", Integer.toString(rate));
        env.put("SECONDS", Integer.toString(runSeconds)); env.put("VUS", Integer.toString(vus)); env.put("K6_NO_USAGE_REPORT", "true");
        Process load = process.redirectErrorStream(true).redirectOutput(directory.resolve("k6.log").toFile()).start();
        var sampler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "perf-resource-sampler"); thread.setDaemon(true); return thread;
        });
        sampler.scheduleWithFixedDelay(() -> sampleResources(baseUrl, directory), 0, 5, TimeUnit.SECONDS);
        try {
            if (!load.waitFor(runSeconds + 90L, TimeUnit.SECONDS)) { load.destroyForcibly(); throw new IllegalStateException("k6 timeout; raw output retained at " + directory); }
        } finally { sampler.shutdown(); if (!sampler.awaitTermination(45, TimeUnit.SECONDS)) sampler.shutdownNow(); }
        record.put("k6ExitCode", load.exitValue()); record.put("elapsedSeconds", (System.nanoTime() - start) / 1_000_000_000.0);
        JsonNode summary = json.readTree(directory.resolve("summary.json").toFile());
        long requests = Math.round(metric(summary, "participation_requests", "count"));
        long finalResponses = Math.round(metric(summary, "confirmed_results", "count"));
        long rejected = Math.round(metric(summary, "business_rejections", "count"));
        long throttle = Math.round(metric(summary, "throttled_requests", "count"));
        long dropped = Math.round(metric(summary, "dropped_iterations", "count"));
        double systemErrors = metric(summary, "system_error_rate", "value");
        record.put("requests", requests); record.put("confirmedResponses", finalResponses); record.put("businessRejections", rejected);
        record.put("throttledRequests", throttle); record.put("droppedIterations", dropped); record.put("systemErrorRate", systemErrors);
        record.put("systemErrorRateSource", "summary.metrics.system_error_rate.value");
        record.put("arrivalRps", requests / (double) runSeconds); record.put("confirmedResponseRps", finalResponses / (double) runSeconds);
        record.put("admissionMs", metrics(summary, "admission_ms")); record.put("confirmedE2eMs", metrics(summary, "confirmed_e2e_ms"));
        List<String> failures = new ArrayList<>();
        List<String> reviewReasons = new ArrayList<>(protocolShortfalls());
        if (load.exitValue() != 0 || dropped != 0 || systemErrors != 0) failures.add("k6 threshold/system-error/dropped-iteration gate");
        if (requests < rate * runSeconds * 0.99) failures.add("achieved arrivals below 99% of target");
        long expectedOrders = kind.equals("replay") ? Math.min(50, requests)
            : kind.equals("mixed") ? (requests + 1) / 2 : finalResponses;
        try {
            await().atMost(Duration.ofSeconds(60)).ignoreExceptions().until(() ->
                count(jdbc, "SELECT COUNT(*) FROM orders WHERE activity_id=?", activity) >= expectedOrders
                && count(jdbc, "SELECT COUNT(*) FROM stock_reservation WHERE activity_id=? AND status NOT IN ('CONFIRMED','CANCELLED')", activity) == 0);
            if (async) await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() -> admin.examineConsumeStats(consumerGroup, topic).computeTotalDiff() == 0);
        } catch (Exception unresolved) { failures.add("DB/reservation/MQ did not converge within deadline: " + unresolved.getClass().getSimpleName()); }
        long orderCount = count(jdbc, "SELECT COUNT(*) FROM orders WHERE activity_id=?", activity);
        long dbStock = count(jdbc, "SELECT available_stock FROM activity_stock WHERE activity_id=?", activity);
        long duplicate = count(jdbc, "SELECT COUNT(*) FROM (SELECT user_id FROM orders WHERE activity_id=? GROUP BY user_id HAVING COUNT(*)>1) d", activity);
        long pending = count(jdbc, "SELECT COUNT(*) FROM stock_reservation WHERE activity_id=? AND status NOT IN ('CONFIRMED','CANCELLED')", activity);
        long confirmedReservations = count(jdbc, "SELECT COUNT(*) FROM stock_reservation WHERE activity_id=? AND status='CONFIRMED'", activity);
        long cancelledReservations = count(jdbc, "SELECT COUNT(*) FROM stock_reservation WHERE activity_id=? AND status='CANCELLED'", activity);
        record.put("orders", orderCount); record.put("dbStock", dbStock); record.put("duplicateOrders", duplicate);
        record.put("nonterminalReservations", pending); record.put("confirmedReservations", confirmedReservations); record.put("cancelledReservations", cancelledReservations);
        double settledSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
        record.put("settledElapsedSeconds", settledSeconds);
        record.put("committedOrderRps", orderCount / settledSeconds);
        record.put("configuredWindowOrderRps", orderCount / (double) runSeconds);
        boolean stockConservationPassed = dbStock >= 0 && orderCount <= stock && dbStock + orderCount == stock;
        boolean uniquenessPassed = duplicate == 0;
        boolean orderResponseAccountingPassed = orderCount == expectedOrders;
        boolean reservationsTerminalPassed = pending == 0;
        record.put("stockConservationPassed", stockConservationPassed);
        record.put("uniquenessPassed", uniquenessPassed);
        record.put("orderResponseAccountingPassed", orderResponseAccountingPassed);
        record.put("reservationsTerminalPassed", reservationsTerminalPassed);
        if (!stockConservationPassed) failures.add("DB stock conservation/oversell invariant");
        if (!uniquenessPassed) failures.add("duplicate valid orders");
        if (!orderResponseAccountingPassed) failures.add("order/confirmed-response accounting mismatch; this is not itself a stock violation");
        if (!reservationsTerminalPassed) failures.add("nonterminal reservations remain");
        if (async) {
            long redisStock = store.getAvailableStock(activity); record.put("redisStock", redisStock);
            record.put("redisStockConservationPassed", redisStock == dbStock);
            record.put("confirmedReservationOrderAlignmentPassed", confirmedReservations == orderCount);
            if (redisStock != dbStock || confirmedReservations != orderCount) failures.add("Redis/DB/durable reservation invariant");
            try {
                long lag = admin.examineConsumeStats(consumerGroup, topic).computeTotalDiff(); record.put("brokerLag", lag);
                if (lag != 0) failures.add("broker lag not zero");
                long messages = brokerMessages() - messagesBefore; record.put("brokerCommittedMessages", messages);
                long additionalMessages = Math.max(0, messages - orderCount);
                record.put("brokerAdditionalMessages", additionalMessages);
                record.put("brokerMessagesPerOrder", orderCount > 0 ? messages / (double) orderCount : null);
                record.put("brokerAdditionalMessageAttribution", "Offset delta only: may include at-least-once commits or late previous-run messages; not key-attributed duplicate count");
                if (messages < orderCount) failures.add("broker committed-message count below confirmed order count");
                if (!kind.equals("replay") && additionalMessages > 0)
                    reviewReasons.add("additional broker messages require key-level attribution before performance PASS");
                String dlq = "%DLQ%" + consumerGroup;
                if (admin.fetchAllTopicList().getTopicList().contains(dlq)) {
                    long dlqCount = admin.examineTopicStats(dlq).getOffsetTable().values().stream().mapToLong(o -> o.getMaxOffset() - o.getMinOffset()).sum();
                    record.put("dlqMessages", dlqCount); if (dlqCount != 0) failures.add("unexpected DLQ messages");
                } else record.put("dlqMessages", 0);
            } catch (Exception unavailable) { failures.add("broker offsets unavailable"); }
        } else record.put("redisStock", "N/A: synchronous MySQL path does not reserve Redis");
        if (kind.equals("success") && (rejected != 0 || finalResponses != requests)) failures.add("all-success traffic rejected or incomplete");
        if (kind.equals("soldout") && (orderCount != stock || rejected == 0)) failures.add("soldout scenario did not exhaust stock and reject excess");
        if (kind.equals("limiter") && (throttle == 0 || finalResponses == 0)) failures.add("limiter comparison lacks both accept and throttle observations");
        if (kind.equals("mixed") && (finalResponses != expectedOrders || rejected != requests / 2 || throttle != 0))
            failures.add("mixed traffic must confirm one order per user pair and reject the other distinct request");
        Files.writeString(directory.resolve("redis-info.txt"), template.execute((org.springframework.data.redis.core.RedisCallback<String>) connection ->
            connection.serverCommands().info().toString()));
        Files.writeString(directory.resolve("docker-after.json"), command("docker", "stats", "--no-stream", "--format", "{{json .}}", MYSQL.getContainerId(), redis.getContainerId()));
        capturePrometheus(baseUrl, directory.resolve("prometheus-after.txt"));
        boolean telemetryComplete = Files.exists(directory.resolve("resources.ndjson")) && !Files.exists(directory.resolve("resource-sampling-errors.txt"));
        if (!telemetryComplete) reviewReasons.add("periodic resource telemetry incomplete");
        record.put("resourceTelemetryComplete", telemetryComplete);
        record.put("reviewReasons", reviewReasons);
        record.put("failures", failures); record.put("technicalStatus", failures.isEmpty() ? "PASS" : "FAIL");
        record.put("benchmarkStatus", failures.isEmpty() ? (warmup ? "EXCLUDED_WARMUP" : reviewReasons.isEmpty() ? "PASS" : "REVIEW") : "FAIL");
        record.put("end", Instant.now().toString()); write(directory.resolve("invariants.json"), record);
        if (!warmup) { results.add(record); writeAggregate(); }
        else {
            warmups.add(record); writeAggregate();
            assertThat(failures).as("warmup correctness must pass before measured traffic; raw evidence=" + directory).isEmpty();
        }
        System.out.println("FLASHGUARD_HTTP_BENCHMARK scenario=" + scenario + " run=" + repetition + " status=" + record.get("benchmarkStatus") + " orders=" + orderCount + " result=" + directory);
        // A failed measured run is retained before stopping; do not spend later rounds at a broken load.
        assertThat(failures).as("measured correctness/arrival failure stops later rounds; raw evidence=" + directory).isEmpty();
    }

    private void writeEnvironment(String sha) throws Exception {
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("gitSha", sha); environment.put("gitStatus", command("git", "status", "--porcelain"));
        String diff = command("git", "diff", "HEAD", "--binary"); Files.writeString(output.resolve("working-tree.patch"), diff);
        environment.put("dirtyDiffSha256", hash(diff.getBytes(StandardCharsets.UTF_8)));
        // Include untracked source content: a plain git diff does not identify newly added code.
        MessageDigest sources = MessageDigest.getInstance("SHA-256");
        try (var paths = Files.walk(Path.of("."))) {
            for (Path path : paths.filter(Files::isRegularFile).filter(p -> {
                String name = p.toString().replace('\\', '/');
                return (name.startsWith("./src/") || name.startsWith("./benchmark/k6/") || name.equals("./pom.xml"));
            }).sorted().toList()) { sources.update(path.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8)); sources.update(Files.readAllBytes(path)); }
        }
        environment.put("sourceTreeSha256", HexFormat.of().formatHex(sources.digest()));
        environment.put("scriptSha256", hash(Files.readAllBytes(Path.of("benchmark/k6/http-full-path.js"))));
        environment.put("java", System.getProperty("java.runtime.version")); environment.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        environment.put("jvmMaxMemory", Runtime.getRuntime().maxMemory()); environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        environment.put("cpuModel", System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unavailable"));
        environment.put("logicalProcessors", Runtime.getRuntime().availableProcessors());
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os)
            environment.put("hostPhysicalMemory", os.getTotalMemorySize());
        environment.put("dockerInfo", command("docker", "info", "--format", "{{json .}}"));
        environment.put("mysqlHostConfig", command("docker", "inspect", "--format", "{{json .HostConfig}}", MYSQL.getContainerId()));
        environment.put("redisHostConfig", command("docker", "inspect", "--format", "{{json .HostConfig}}", redis.getContainerId()));
        environment.put("mysqlVersion", "8.4.8"); environment.put("redisVersion", "7.4.8"); environment.put("rocketMqVersion", "5.3.2");
        environment.put("mqRuntime", broker.runtimeMetadata());
        Map<String, String> mqJarHashes = new TreeMap<>();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String entry : classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            Path path = Path.of(entry);
            if (Files.isRegularFile(path) && path.getFileName().toString().startsWith("rocketmq-") && entry.endsWith(".jar"))
                mqJarHashes.put(path.getFileName().toString(), hash(Files.readAllBytes(path)));
        }
        environment.put("rocketMqJarSha256", mqJarHashes);
        environment.put("springBootVersion", org.springframework.boot.SpringBootVersion.getVersion()); environment.put("k6Version", command(k6, "version"));
        environment.put("dbPoolSize", 20); environment.put("consumerThreads", "4..8"); environment.put("topicQueues", 1);
        environment.put("schedulingEnabled", false);
        environment.put("backgroundWorkBoundary", "Scheduled compensation, orphan recovery and reconciliation are disabled during measured HTTP load; separate real fault tests validate recovery. Results are not total production capacity.");
        environment.put("redisPersistence", "appendonly=no, save disabled"); environment.put("brokerLogs", broker.directory().toString());
        environment.put("resourceLimitBoundary", "containers/JVM share this host; HostConfig records actual limits; no dedicated benchmark machine");
        environment.put("isolation", "fresh owned containers and broker; every measured run uses unique activity/request/user namespace; offsets must drain");
        environment.put("repetitions", repetitions); environment.put("seconds", seconds); environment.put("warmupSeconds", warmupSeconds);
        environment.put("protocolShortfalls", protocolShortfalls()); environment.put("rate", rate); environment.put("vus", vus);
        Files.copy(broker.directory().resolve("broker.conf"), output.resolve("broker.conf"), StandardCopyOption.REPLACE_EXISTING);
        write(output.resolve("environment.json"), environment);
    }
    private void writeAggregate() throws Exception {
        if (output == null) return;
        Map<String, Object> aggregate = new LinkedHashMap<>(); aggregate.put("runs", results);
        aggregate.put("warmupsExcludedFromStatistics", warmups); aggregate.put("suiteCompleted", suiteCompleted);
        Map<String, Object> comparisons = new LinkedHashMap<>();
        for (String scenario : results.stream().map(r -> r.get("scenario").toString()).distinct().toList()) {
            List<Map<String, Object>> rows = results.stream().filter(r -> r.get("scenario").equals(scenario)).toList();
            double[] rps = rows.stream().mapToDouble(r -> ((Number) r.get("committedOrderRps")).doubleValue()).sorted().toArray();
            Map<String, Object> stats = new LinkedHashMap<>(); stats.put("runs", rows.size());
            stats.put("status", rows.stream().anyMatch(r -> r.get("technicalStatus").equals("FAIL")) ? "FAIL" : rows.size() >= 5 && rows.stream().allMatch(r -> r.get("benchmarkStatus").equals("PASS")) ? "PASS" : "REVIEW");
            stats.put("committedOrderRpsMin", rps[0]); stats.put("committedOrderRpsMedian", rps.length % 2 == 1 ? rps[rps.length / 2] : (rps[rps.length / 2 - 1] + rps[rps.length / 2]) / 2);
            stats.put("committedOrderRpsMax", rps[rps.length - 1]);
            for (String field : List.of("arrivalRps", "confirmedResponseRps", "committedOrderRps"))
                stats.put(field, distribution(rows.stream().mapToDouble(r -> ((Number) r.get(field)).doubleValue()).toArray()));
            for (String field : List.of("admissionMs", "confirmedE2eMs")) {
                Map<String, Object> latency = new LinkedHashMap<>();
                for (String percentile : List.of("med", "p(95)", "p(99)"))
                    latency.put(percentile, distribution(rows.stream().mapToDouble(r -> ((JsonNode) r.get(field)).path(percentile).asDouble()).toArray()));
                stats.put(field, latency);
            }
            comparisons.put(scenario, stats);
        }
        aggregate.put("comparisons", comparisons);
        String overall = results.stream().anyMatch(r -> r.get("benchmarkStatus").equals("FAIL"))
            || warmups.stream().anyMatch(r -> r.get("technicalStatus").equals("FAIL")) ? "FAIL"
            : !suiteCompleted || results.isEmpty() || results.stream().anyMatch(r -> !r.get("benchmarkStatus").equals("PASS"))
                || comparisons.values().stream().anyMatch(value -> !((Map<?, ?>) value).get("status").equals("PASS")) ? "REVIEW" : "PASS";
        aggregate.put("overallStatus", overall);
        aggregate.put("boundary", "Configured-rate HTTP evidence with scheduled compensation/reconciliation disabled; not total production capacity, maximum capacity, million-request, million-row or Redis Cluster evidence");
        write(output.resolve("aggregate.json"), aggregate);
    }
    private double metric(JsonNode summary, String name, String key) {
        JsonNode values = requiredMetric(summary, name);
        JsonNode field = values.get(key);
        if (field == null || !field.isNumber() || !Double.isFinite(field.doubleValue()))
            throw new IllegalStateException("Required numeric k6 summary field missing/invalid: metrics." + name + "." + key);
        return field.doubleValue();
    }
    private JsonNode requiredMetric(JsonNode summary, String name) {
        JsonNode node = summary.path("metrics").get(name);
        if (node == null || !node.isObject()) throw new IllegalStateException("Required k6 metric missing: " + name);
        return node.has("values") ? node.get("values") : node;
    }
    private JsonNode metrics(JsonNode summary, String name) {
        for (String percentile : List.of("med", "p(95)", "p(99)")) metric(summary, name, percentile);
        return requiredMetric(summary, name);
    }
    private long count(JdbcTemplate jdbc, String sql, long activity) { return jdbc.queryForObject(sql, Long.class, activity); }
    private long brokerMessages() throws Exception { return admin.examineTopicStats(topic).getOffsetTable().values().stream().mapToLong(o -> o.getMaxOffset()).sum(); }
    private List<String> protocolShortfalls() {
        List<String> shortfalls = new ArrayList<>();
        if (warmupSeconds < 60) shortfalls.add("warmup below plan 60 seconds");
        if (seconds < 180) shortfalls.add("steady duration below plan 180 seconds");
        if (repetitions < 5) shortfalls.add("fewer than plan 5 measured repetitions");
        return shortfalls;
    }
    private Map<String, Double> distribution(double[] samples) {
        Arrays.sort(samples); int n = samples.length;
        return Map.of("min", samples[0], "median", n % 2 == 1 ? samples[n / 2] : (samples[n / 2 - 1] + samples[n / 2]) / 2, "max", samples[n - 1]);
    }
    private void sampleResources(String baseUrl, Path directory) {
        String timestamp = Instant.now().toString();
        try {
            List<String> arguments = new ArrayList<>(List.of("docker", "stats", "--no-stream", "--format", "{{json .}}", MYSQL.getContainerId(), redis.getContainerId()));
            String mqContainer = broker.brokerContainerId();
            if (mqContainer != null) arguments.add(mqContainer);
            Map<String, Object> sample = new LinkedHashMap<>(); sample.put("timestamp", timestamp);
            sample.put("dockerStats", command(arguments.toArray(String[]::new)));
            sample.put("applicationProcessCpuNanos", ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toNanos());
            sample.put("applicationHeapBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            Files.writeString(directory.resolve("resources.ndjson"), json.writeValueAsString(sample) + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            capturePrometheus(baseUrl, directory.resolve("prometheus-" + timestamp.replace(':', '-') + ".txt"));
        } catch (Exception failure) {
            try { Files.writeString(directory.resolve("resource-sampling-errors.txt"), timestamp + " " + failure.getClass().getSimpleName() + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (Exception ignored) { /* preserve the main workload result even when telemetry cannot be saved */ }
        }
    }
    private void write(Path path, Object value) throws Exception { json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value); }
    private String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private String command(String... arguments) throws Exception {
        Path file = Files.createTempFile("flashguard-perf-command-", ".txt");
        try {
            Process process = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(file.toFile()).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Command timeout: " + arguments[0]); }
            String text = Files.readString(file); if (process.exitValue() != 0) throw new IllegalStateException(arguments[0] + " failed: " + text);
            return text;
        } finally { deleteAfterProcessExit(file); }
    }
    private void deleteAfterProcessExit(Path file) throws Exception {
        FileSystemException locked = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try { Files.deleteIfExists(file); return; }
            catch (FileSystemException retryable) {
                locked = retryable;
                Thread.sleep(100L);
            }
        }
        throw locked;
    }
    private void capturePrometheus(String baseUrl, Path target) throws Exception {
        PrometheusCapture.capture(baseUrl, target);
    }

    // Static nested helper allows HTTP contract tests without initializing the benchmark's containers.
    static final class PrometheusCapture {
        static void capture(String baseUrl, Path target) throws Exception {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/prometheus"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            // Retain even failed scrapes before rejecting them, so telemetry failures remain diagnosable.
            Files.writeString(target, response.body());
            Files.writeString(target.resolveSibling(target.getFileName() + ".http-status.txt"),
                Integer.toString(response.statusCode()));
            if (response.statusCode() != 200)
                throw new IllegalStateException("Prometheus scrape HTTP " + response.statusCode() + ": " + target);
            for (String metric : List.of("application_started_time_seconds", "jvm_memory_used_bytes", "process_uptime_seconds")) {
                var samples = java.util.regex.Pattern.compile("(?m)^" + metric + "(?:\\{[^\\r\\n]*\\})?[ \\t]+([^ \\t\\r\\n]+)(?:[ \\t]|\\r?$)")
                    .matcher(response.body());
                boolean valid = false;
                while (samples.find()) {
                    try {
                        double value = Double.parseDouble(samples.group(1));
                        if (Double.isFinite(value) && value >= 0) valid = true;
                    } catch (NumberFormatException invalidSample) { /* not a usable metric sample */ }
                }
                if (!valid) throw new IllegalStateException("Prometheus scrape lacks valid " + metric + ": " + target);
            }
        }
    }
}
