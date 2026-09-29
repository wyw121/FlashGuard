# Stage 7: real Redis Cluster inventory A/B, 2026-09-13

**Component correctness: PASS. Inventory buckets: do not adopt in production from this evidence.** All ten real Cluster trials completed, but the measured throughput advantage is inconsistent and tail latency does not improve consistently. Redis process CPU does not demonstrate single-primary saturation.

## Evidence and revision

The run completed at `2026-09-13T11:14:33.944141500Z` (19:14:33 Asia/Shanghai). The retained [JUnit report](stage7-ece8209ec152-dirty/RedisClusterBucketBenchmarkTest.txt) records **1 test, 0 failures, 0 errors, 0 skipped, 119.9 s**. The [raw A/B JSON](stage7-ece8209ec152-dirty/stage7-cluster-bucket-ab.json) has `completed=true`, `completedTrials=10`, all ten `correctnessPassed=true`, and 50,000 raw request latency samples.

Base Git revision: `ece8209ec152395a9a092a214b7eafab443288ed`, **dirty working tree**. The test and other implementation changes were uncommitted; this is not a benchmark of that commit alone or a published release. Relevant sources were copied after the passing run under [sources](stage7-ece8209ec152-dirty/sources/) and recorded in the [source SHA-256 manifest](stage7-ece8209ec152-dirty/source-sha256.json). This preserves the benchmark/Lua source snapshot, not a full buildable repository release. The [environment record](stage7-ece8209ec152-dirty/environment.json) distinguishes values recorded by the benchmark from Docker metadata inspected after the run.

Raw A/B JSON SHA-256: `7f98ea1e6b37451c8b9aa5e7a28a37782f9c6c4522ef88ce0a1db633da23d1cd`. Preserve this retained copy; subsequent runs overwrite the file in `target`.

## Controlled configuration

Windows 11 client, JDK 21.0.9, 12 processors reported by the JVM; Redis image `redis:7.4.8`, local image ID `sha256:7ceda270a3137de18c0b3aff75fa0002faa1c2a87e2b25e86978a1c3f20b3b78`. Docker Engine 28.3.3 runs Linux/WSL2; the later inspection reported 12 Docker VM CPUs and 16,518,426,624 memory bytes. Exclusive host-resource isolation was not established.

Both treatments use the **same three actual Redis Cluster primaries**, each limited to one CPU core and 256 MiB, with no replicas and persistence disabled. Total quota stays three cores / 768 MiB for A and B. The test checks Redis version, Cluster state, full slot coverage and primary ownership. It uses direct mapped-port connections to known owners, not a production topology-discovery/failover client.

Each treatment uses 16 synchronized workers and 5,000 unique user/request pairs. Before each trial, all three dedicated primary databases are cleared and each primary receives 200 excluded warmup reservations. Treatment order alternates: A/B, B/A, A/B, B/A, A/B. Both treatments execute the unchanged production Lua resources and include UUID generation and JSON decoding in client timing.

| Treatment | Primary / stock-key slot | Allocated stock per trial |
| --- | --- | --- |
| A: single hot inventory key | Primary 1 / slot 5550 | 5,000 |
| B: deterministic three buckets | Primary 0 / slot 2197; primary 1 / slot 6324; primary 2 / slot 14582 | 1,666; 1,667; 1,667 |

Routing is `userId % bucketCount`, and inventory exactly matches each bucket's demand. Every trial produced 5,000 successes, no unexpected decisions, zero remaining stock and 5,000 pending/request/user records in aggregate. The ten trials therefore contain **50,000 measured successes**, plus 6,000 excluded warmup successes. The test also checks one replay and one same-user duplicate per trial outside timing. These are Redis PENDING records, not durable reservations or completed orders.

## Five-round measurements

Each entry is the **median [minimum, maximum] of five per-trial values**. Latency percentiles use nearest rank within each trial; the table does not claim pooled percentiles across all 25,000 requests per treatment.

| Metric | A: single hot key | B: three primary buckets |
| --- | --- | --- |
| Successful reservations/s | 1,057.87 [600.80, 1,166.84] | 1,113.97 [626.51, 1,193.47] |
| P50 ms | 14.6532 [13.8352, 24.0050] | 13.8374 [12.8333, 22.6304] |
| P95 ms | 24.5714 [21.0152, 50.8825] | 24.2727 [22.1059, 46.3560] |
| P99 ms | 32.2224 [28.2424, 65.2601] | 32.8943 [26.9112, 71.4937] |

Paired changes use `100 × (B / A − 1)` within each round; a negative P99 change means lower latency.

| Round / order | A reservations/s | B reservations/s | Throughput change | A P99 ms | B P99 ms | P99 change |
| --- | --- | --- | --- | --- | --- | --- |
| 1 / A then B | 600.80 | 751.71 | +25.12% | 65.2601 | 71.4937 | +9.55% |
| 2 / B then A | 1,166.84 | 1,193.47 | +2.28% | 28.2424 | 32.8943 | +16.47% |
| 3 / A then B | 1,073.92 | 1,146.37 | +6.75% | 35.8548 | 26.9112 | −24.94% |
| 4 / B then A | 1,057.87 | 626.51 | −40.78% | 31.0644 | 69.4471 | +123.56% |
| 5 / A then B | 1,042.30 | 1,113.97 | +6.88% | 32.2224 | 32.8348 | +1.90% |

The ratio of throughput medians is +5.30%; the median paired throughput change is +6.75%. Neither is evidence of a stable capacity increase: round 4 reverses the apparent gain, and bucket P99 is worse in four of five pairs. No cause for the observed fluctuations was isolated by this experiment.

## Redis CPU and metric interpretation

| Round | A loaded primary 1 CPU | B primary 0 CPU | B primary 1 CPU | B primary 2 CPU |
| --- | --- | --- | --- | --- |
| 1 | 17.06% | 8.65% | 8.90% | 8.88% |
| 2 | 38.37% | 14.90% | 14.62% | 14.95% |
| 3 | 32.85% | 15.10% | 14.96% | 14.79% |
| 4 | 31.93% | 7.59% | 7.70% | 7.63% |
| 5 | 31.36% | 13.62% | 13.68% | 13.84% |

Percentages are each Redis process's `used_cpu_sys + used_cpu_user` delta divided by its INFO interval; **100% means one CPU core**, not the whole host. The loaded single primary ranges from 17.06% to 38.37%; bucket primaries range from 7.59% to 15.10%. This demonstrates distributed work but does not establish that the single Redis execution thread is saturated. INFO snapshots are sequential, intervals include sampling/housekeeping, and CPU is process-wide. The raw file retains per-primary user/system deltas, command deltas and before/after instantaneous ops/s.

`total_commands_processed` includes Lua internal commands, INFO and Cluster traffic. Command ops/s must not be relabeled successful reservations/s. Client latency includes JVM scheduling, serialization, Docker and Windows networking; low Redis CPU alone does not identify which of those limits throughput. Same resource quotas do not guarantee an otherwise idle host.

## Standalone baseline context

The earlier [standalone JSON](stage7-ece8209ec152-dirty/stage7-hot-key-baseline.json), recorded at `2026-09-13T08:38:48.491703Z`, separately reports 5,000/5,000 successes, stock 0, pending 5,000; 530.52 successful reservations/s, P50 25.6339 ms, P95 64.5061 ms, P99 114.1191 ms, and 15.14% one-core process CPU. Its Redis command delta is 120,001 over 9.4341 s, approximately 12,719.93 command ops/s. This earlier one-run standalone measurement is **not** the A side of the controlled Cluster comparison and does not share its explicit quota configuration. Do not compute a scaling claim by comparing it with bucket throughput.

## Decision and remaining boundary

The Stage 7 component comparison and correctness evidence are complete for this configuration. **Do not adopt inventory buckets in production**: a repeatable material benefit under demonstrated single-node saturation has not been established. Keep deterministic routing, within-slot Lua atomicity and DB global uniqueness as requirements if future demand justifies further work. A sustained concurrency/load sweep, skew/depletion and rebalancing behavior, failover and production Cluster-client handling remain separate work before adoption. This result establishes no HTTP, database, MQ or full order-chain capacity.

Reproduction command (Docker running; from this working tree with its uncommitted implementation present):

```powershell
Set-Location -LiteralPath 'D:\code\flashguard-workbench\flashguard'
mvn -s '..\work-notes\maven-settings.xml' '-Dtest=RedisClusterBucketBenchmarkTest' '-Dflashguard.benchmark.cluster.iterations=5000' '-Dflashguard.benchmark.cluster.workers=16' '-Dflashguard.benchmark.cluster.rounds=5' test
```

The workspace Maven settings are local infrastructure, not a portable project requirement; in an environment with working dependency resolution, omit `-s`. Pass criteria: one passing test without skips, `completed=true`, exactly ten trials, 50,000 successes, zero unexpected decisions and every bucket inventory assertion satisfied. Repeated timings are expected to vary; no performance threshold is asserted.
