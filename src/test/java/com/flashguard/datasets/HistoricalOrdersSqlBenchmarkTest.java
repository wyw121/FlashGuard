package com.flashguard.datasets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Historical SQL experiment, not an ingress/load test. Always owns a fresh disposable MySQL container. */
@EnabledIfSystemProperty(named="flashguard.sql-benchmark.enabled", matches="true")
class HistoricalOrdersSqlBenchmarkTest {
    private static final Path DATASETS = Path.of("benchmark","datasets");
    private static final int WARMUPS = 5;
    private static final int REPETITIONS = 20;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String,Object> measurements = new LinkedHashMap<>();
    private Path output;

    @Test
    @Timeout(value=30,unit=TimeUnit.MINUTES)
    void millionHistoricalOrdersCompareIndexesAndEquivalentDeepPagination() throws Exception {
        String run = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
            + "-" + UUID.randomUUID().toString().substring(0,8);
        output = DATASETS.resolve("results").resolve(run).toAbsolutePath();
        Files.createDirectories(output);
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("startedAt",Instant.now().toString());
        report.put("outcome","RUNNING");
        report.put("scope","fresh experiment-only historical_orders; no production index migration and no HTTP throughput claim");
        report.put("warmupRepetitions",WARMUPS);
        report.put("measuredRepetitions",REPETITIONS);
        report.put("cacheState","warm-per-query after five full fetches; no cold-cache claim; shared host");
        report.put("measurements",measurements);
        writeJson("summary.json",report);
        try (var mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4.8"))
            .withDatabaseName("flashguard_sql_experiment").withUsername("experiment").withPassword("experiment-only")
            .withCommand("--innodb-buffer-pool-size=268435456")) {
            mysql.start();
            try (Connection db = DriverManager.getConnection(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword())) {
                recordEnvironment(mysql,db);
                runScript(db,"historical-orders-schema.sql");
                List<Map<String,Object>> batches = new ArrayList<>();
                String seed = sqlFile("historical-orders-seed-batch.sql");
                for(int batch=0;batch<10;batch++) {
                    execute(db,"SET @batch = " + batch);
                    long begin = System.nanoTime();
                    int inserted = execute(db,seed);
                    assertThat(inserted).isEqualTo(100000);
                    batches.add(Map.of("batch",batch,"rows",inserted,"elapsedMs",(System.nanoTime()-begin)/1_000_000.0));
                    writeJson("seed-batches.json",batches);
                }
                List<List<String>> counts = rows(db,"SELECT COUNT(*),MIN(id),MAX(id),COUNT(DISTINCT activity_id),COUNT(DISTINCT user_id),SUM(status='CONFIRMED') FROM historical_orders");
                assertThat(counts).containsExactly(List.of("1000000","1","1000000","100","10000","900000"));
                writeJson("dataset-counts.json",counts);
                report.put("datasetRows",1000000);
                report.put("datasetSha256",datasetHash(db));
                execute(db,"ANALYZE TABLE historical_orders");
                writeJson("indexes-before.json",rows(db,"SHOW INDEX FROM historical_orders"));
                String user = sqlFile("query-user.sql");
                String activity = sqlFile("query-activity.sql");
                var userBefore = measure(db,"user-before",user);
                var activityBefore = measure(db,"activity-before",activity);
                runScript(db,"historical-orders-candidate-indexes.sql");
                execute(db,"ANALYZE TABLE historical_orders");
                var userAfter = measure(db,"user-after",user);
                var activityAfter = measure(db,"activity-after",activity);
                assertThat(userAfter).isEqualTo(userBefore);
                assertThat(activityAfter).isEqualTo(activityBefore);
                runScript(db,"historical-orders-pagination-index.sql");
                execute(db,"ANALYZE TABLE historical_orders");
                String cursorSql = sqlFile("query-cursor-acquisition.sql");
                long cursorStart = System.nanoTime();
                var cursor = rows(db,cursorSql);
                double cursorMs = (System.nanoTime()-cursorStart)/1_000_000.0;
                assertThat(cursor).hasSize(1);
                writeJson("cursor-acquisition.json",Map.of("sql",cursorSql,"elapsedMs",cursorMs,"rows",cursor,
                    "boundary","Cursor discovery incurs deep-offset cost once and is excluded from per-page keyset timing; keyset assumes cursor already known"));
                String keyset = sqlFile("query-keyset.sql").replace("{{cursor_created_at}}",cursor.get(0).get(1))
                    .replace("{{cursor_id}}",cursor.get(0).get(0));
                var offsetWindow = measure(db,"deep-offset",sqlFile("query-deep-offset.sql"));
                var keysetWindow = measure(db,"keyset",keyset);
                assertThat(offsetWindow).hasSize(50);
                assertThat(keysetWindow).isEqualTo(offsetWindow);
                writeJson("indexes-after.json",rows(db,"SHOW INDEX FROM historical_orders"));
                report.put("equivalence",Map.of("userBeforeAfter",true,"activityBeforeAfter",true,"offsetKeysetWindow",true));
                report.put("comparisons",Map.of("user",comparison("user-before","user-after"),
                    "activity",comparison("activity-before","activity-after"),"pagination",comparison("deep-offset","keyset")));
                report.put("outcome","PASS");
                report.put("conclusionBoundary","PASS means one million deterministic rows and identical result windows verified with complete raw timings/plans; speedup is an observation, never a hardcoded pass threshold");
            }
        } catch (Throwable failure) {
            report.put("outcome","FAIL");
            report.put("failure",failure.getClass().getName()+": "+failure.getMessage());
            throw failure;
        } finally {
            report.put("completedAt",Instant.now().toString());
            writeJson("summary.json",report);
            System.out.println("FLASHGUARD_SQL_BENCHMARK_OUTPUT="+output);
        }
    }

    private List<List<String>> measure(Connection db,String name,String sql) throws Exception {
        Path dir=output.resolve(name); Files.createDirectories(dir);
        Files.writeString(dir.resolve("query.sql"),sql+";\n");
        Files.writeString(dir.resolve("explain-analyze.txt"),String.join("\n",rows(db,"EXPLAIN ANALYZE "+sql).stream().map(r->r.get(0)).toList()));
        Files.writeString(dir.resolve("explain-json.txt"),rows(db,"EXPLAIN FORMAT=JSON "+sql).get(0).get(0));
        List<List<String>> expected = rows(db,sql);
        assertThat(expected).hasSize(50);
        List<Double> warmup = new ArrayList<>();
        List<Double> measured = new ArrayList<>();
        for(int i=0;i<WARMUPS+REPETITIONS;i++) {
            long start=System.nanoTime();
            List<List<String>> actual=rows(db,sql);
            double ms=(System.nanoTime()-start)/1_000_000.0;
            assertThat(actual).isEqualTo(expected);
            if(i<WARMUPS) warmup.add(ms); else measured.add(ms);
        }
        Files.writeString(dir.resolve("result-window.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(expected));
        List<Double> sorted=measured.stream().sorted().toList();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("querySha256",hash(sql.getBytes(StandardCharsets.UTF_8)));
        result.put("warmupMsExcluded",warmup);result.put("rawMs",measured);
        result.put("medianMs",(sorted.get(9)+sorted.get(10))/2.0);
        result.put("p95Ms",sorted.get((int)Math.ceil(.95*sorted.size())-1));
        result.put("minMs",sorted.get(0));result.put("maxMs",sorted.get(sorted.size()-1));
        result.put("timingBoundary","JDBC execute plus full result fetch/materialization, excludes equality assertions and EXPLAIN ANALYZE");
        result.put("resultSha256",hash(JSON.writeValueAsBytes(expected)));
        Files.writeString(dir.resolve("timings.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        measurements.put(name,result);
        return expected;
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> comparison(String baseline,String candidate) {
        Map<String,Object> a=(Map<String,Object>)measurements.get(baseline);
        Map<String,Object> b=(Map<String,Object>)measurements.get(candidate);
        return Map.of("baseline",baseline,"candidate",candidate,
            "medianBaselineOverCandidate",((Number)a.get("medianMs")).doubleValue()/((Number)b.get("medianMs")).doubleValue(),
            "p95BaselineOverCandidate",((Number)a.get("p95Ms")).doubleValue()/((Number)b.get("p95Ms")).doubleValue());
    }

    private String datasetHash(Connection db) throws Exception {
        String sql="SELECT id,order_no,request_id,activity_id,user_id,reservation_id,status,created_at,updated_at FROM historical_orders ORDER BY id";
        Files.writeString(output.resolve("dataset-hash-query.sql"),sql+";\n");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        long count=0;
        try(Statement statement=db.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)) {
            statement.setFetchSize(Integer.MIN_VALUE);
            try(ResultSet result=statement.executeQuery(sql)) {
                while(result.next()) {
                    for(int col=1;col<=9;col++) {
                        digest.update(result.getString(col).getBytes(StandardCharsets.UTF_8));
                        digest.update((byte)(col==9?'\n':'\t'));
                    }
                    count++;
                }
            }
        }
        assertThat(count).isEqualTo(1000000);
        return HexFormat.of().formatHex(digest.digest());
    }

    private void recordEnvironment(MySQLContainer mysql,Connection db) throws Exception {
        Map<String,Object> env=new LinkedHashMap<>();
        env.put("hostOs",System.getProperty("os.name")+" "+System.getProperty("os.version")+" "+System.getProperty("os.arch"));
        env.put("javaVersion",System.getProperty("java.version"));env.put("javaVendor",System.getProperty("java.vendor"));
        env.put("jvmProcessors",Runtime.getRuntime().availableProcessors());env.put("jvmMaxHeapBytes",Runtime.getRuntime().maxMemory());
        env.put("mysqlImage",mysql.getDockerImageName());env.put("containerId",mysql.getContainerId());
        env.put("mysqlVersionAndSettings",rows(db,"SELECT VERSION(),@@innodb_buffer_pool_size,@@innodb_flush_log_at_trx_commit,@@transaction_isolation,@@character_set_server,@@collation_server,@@sql_mode"));
        env.put("connectorVersion",db.getMetaData().getDriverVersion());
        env.put("dockerHostConfig",command("docker","inspect","--format","{{json .HostConfig}}",mysql.getContainerId()));
        env.put("dockerImageId",command("docker","inspect","--format","{{.Image}}",mysql.getContainerId()));
        env.put("gitSha",command("git","rev-parse","HEAD"));
        env.put("workingTreeStatus",command("git","status","--porcelain"));
        Files.writeString(output.resolve("working-tree.patch"),command("git","diff","HEAD","--","src","pom.xml","benchmark/datasets"));
        Map<String,String> hashes=new TreeMap<>();
        try(var paths=Files.list(DATASETS)) {
            for(Path p:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList()) {
                hashes.put(p.getFileName().toString(),hash(Files.readAllBytes(p)));
                Files.copy(p,output.resolve(p.getFileName()),StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path harness=Path.of("src/test/java/com/flashguard/datasets/HistoricalOrdersSqlBenchmarkTest.java");
        hashes.put(harness.toString(),hash(Files.readAllBytes(harness)));
        Files.copy(harness,output.resolve(harness.getFileName()),StandardCopyOption.REPLACE_EXISTING);
        hashes.put("pom.xml",hash(Files.readAllBytes(Path.of("pom.xml"))));
        env.put("inputSha256",hashes);
        env.put("datasetHashEncoding","id-ordered rows; nine hash-query columns as Connector/J UTF-8 strings, TAB between columns, LF after each row; all columns non-null");
        env.put("resourceBoundary","Owned MySQL container with 256MiB InnoDB buffer pool; actual Docker resource limits recorded; shared host, no CPU isolation or cold-cache claim");
        writeJson("environment.json",env);
    }

    private void runScript(Connection db,String filename) throws Exception {
        for(String statement:sqlFile(filename).split(";")) if(!statement.isBlank()) execute(db,statement.trim());
    }
    private String sqlFile(String filename) throws Exception {
        return Files.readString(DATASETS.resolve(filename)).lines().filter(line->!line.stripLeading().startsWith("--"))
            .reduce("",(a,b)->a+b+"\n").trim().replaceFirst(";\\s*$","");
    }
    private int execute(Connection db,String sql) throws Exception {
        Files.writeString(output.resolve("executed-statements.sql"),sql+";\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        try(Statement statement=db.createStatement()) {statement.execute(sql);return statement.getUpdateCount();}
    }
    private List<List<String>> rows(Connection db,String sql) throws Exception {
        List<List<String>> result=new ArrayList<>();
        try(Statement statement=db.createStatement();ResultSet rs=statement.executeQuery(sql)) {
            int columns=rs.getMetaData().getColumnCount();
            while(rs.next()) {List<String> row=new ArrayList<>();for(int i=1;i<=columns;i++)row.add(rs.getString(i));result.add(row);}
        }
        return result;
    }
    private String hash(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private void writeJson(String filename,Object value) throws Exception {Files.writeString(output.resolve(filename),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));}
    private String command(String...args) throws Exception {
        Path capture=Files.createTempFile(output,"environment-command-",".tmp");
        try {
            Process p=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(capture.toFile()).start();
            if(!p.waitFor(30,TimeUnit.SECONDS)) {p.destroyForcibly();throw new IllegalStateException("Environment command timed out: "+args[0]);}
            String result=Files.readString(capture,StandardCharsets.UTF_8);
            if(p.exitValue()!=0)throw new IllegalStateException("Environment command failed: "+args[0]+": "+result);
            return result.trim();
        } finally {Files.deleteIfExists(capture);}
    }
}
