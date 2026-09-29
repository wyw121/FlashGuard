# ADR 009: Inventory buckets remain conditional on measured Redis saturation

Status: non-adoption for the current production runtime, supported by the real five-round Cluster A/B on 2026-09-13. Component correctness PASS; stable performance benefit and single-primary saturation are not established.

## Context

Stage 7 requires evidence before inventory bucket implementation. The current reservation Lua operates on stock, requests, users, pending and released keys under one activity hash tag (`{activity:7001}`). “Single key baseline” therefore means one hot inventory key and its associated atomic records, not a one-key script. The production runtime remains a standalone Redis node. A separate real Redis Cluster component experiment compares the two inventory layouts without adding a production Cluster routing path.

Splitting an inventory key on the same Redis node does not automatically parallelize Lua execution across CPU cores. Cluster buckets would need placement on different primary nodes. Each bucket's related keys must share its hash tag and slot, while different buckets need different tags and verified primary-node placement. A hash tag alone does not guarantee a different primary.

## Reproducible component baseline

`RedisHotKeyBenchmarkTest#recordsSingleHotKeyLuaComponentBaseline` starts a real `redis:7.4.8` Testcontainer and checks the reported server version. It warms the production store with 200 successful reservations on a separate activity, then measures 5,000 unique user/request reservations against stock 5,000 with 16 synchronized workers. Properties `flashguard.benchmark.iterations` and `flashguard.benchmark.workers` allow controlled sweeps. The test requires Docker and is not skipped when Docker is unavailable.

The generated `target/stage7-hot-key-baseline.json` records elapsed time, successful reservations/s, nearest-rank p50/p95/p99, every client-observed latency sample, Redis INFO CPU deltas, command count delta and instantaneous ops/s. Correctness requires all decisions SUCCESS, remaining stock zero and pending count equal to iterations. These pending reservations are not durable DB records or completed orders.

The CPU percentage divides Redis process `used_cpu_sys + used_cpu_user` deltas by the INFO sampling interval: 100% corresponds to one CPU core. It is not host CPU utilization or proof that Redis's command execution thread is saturated. INFO command counts include Lua internal commands and sampling, so command ops/s must not be labeled reservation throughput. Client latency includes Java execution, serialization, network and Docker/Windows overhead. Container resource limits, host details and Git SHA must accompany retained runs. A single closed-loop run cannot establish maximum sustainable throughput.

## Controlled three-primary Cluster A/B

`RedisClusterBucketBenchmarkTest#comparesSingleHotKeyWithThreePrimaryBucketsOnTheSameCluster` creates three real Redis 7.4.8 primary containers on one Docker network. Each primary has a one-core CPU quota and 256 MiB memory limit. Both treatments run on this **same** Cluster with the same total three-core/768 MiB quota, client and Lua scripts. The single-key treatment directs inventory writes to one primary; the bucket treatment spreads three inventory keys across three verified primary owners. This separates the layout comparison from adding resources between A and B.

The harness reads actual Cluster slot ownership and selects hash tags belonging to different primaries. Each bucket's five keys share its tag. Static direct Lettuce connections to owning nodes through Docker mapped ports avoid advertised-address NAT; this deliberately does not claim production Cluster discovery, failover or resharding support. Both treatments execute the unchanged production Lua resources, generate UUIDs and deserialize `PendingReservation` JSON.

Default execution runs five rounds of 5,000 unique successful reservations at 16 workers per treatment, alternating A/B order each round. Before each treatment it clears all three dedicated databases and warms every primary identically with 200 excluded requests. User routing is deterministic `userId % bucketCount`; stock is allocated to the exact expected demand for each bucket. Thus the experiment measures balanced successful work, not sold-out rejection speed. Assertions verify every bucket's stock, pending count, request count and user count, plus same-user replay and duplicate behavior outside timing. Demand skew, rebalancing and global DB uniqueness are outside this component experiment.

Properties `flashguard.benchmark.cluster.iterations`, `flashguard.benchmark.cluster.workers` and `flashguard.benchmark.cluster.rounds` control the experiment. `target/stage7-cluster-bucket-ab.json` contains all raw per-request latencies, per-trial latency percentiles and successful throughput, per-primary CPU/command metrics, actual topology and inventory assertions. It is updated after each successful trial; require `completed=true`, ten trials at the default five rounds and a passing test result before treating it as completed evidence. INFO boundaries are sampled sequentially for the three primaries; percentages use each primary's own sample interval and include Cluster housekeeping. Do not sum different-interval command rates and call them business throughput.

## Measured evidence: 2026-09-13

Full paired results, CPU observations, reproduction command and limitations are in the [Stage 7 validation report](../validation/2026-09-13/stage7-cluster-ab.md). The [retained raw JSON](../validation/2026-09-13/stage7-ece8209ec152-dirty/stage7-cluster-bucket-ab.json), [environment record](../validation/2026-09-13/stage7-ece8209ec152-dirty/environment.json) and [source hash manifest](../validation/2026-09-13/stage7-ece8209ec152-dirty/source-sha256.json) preserve the evidence independently of `target`.

| Evidence | Result |
| --- | --- |
| Revision | Base `ece8209ec152395a9a092a214b7eafab443288ed` plus uncommitted working-tree implementation; not a result of base HEAD alone |
| Host/container configuration | Windows 11 / JDK 21.0.9; actual Redis 7.4.8 three-primary Cluster; same 1 core / 256 MiB per primary in A and B |
| Cluster A/B execution | 1 test, 0 failures/errors/skips, 119.9 s; five rounds, ten completed trials |
| Successes, remaining stock, pending count | Each trial: 5,000 successes, zero unexpected decisions, remaining 0, pending/request/user records 5,000; 50,000 measured successes overall |
| A single-hot-key reservations/s | Median 1,057.87; min 600.80, max 1,166.84 |
| B three-primary buckets reservations/s | Median 1,113.97; min 626.51, max 1,193.47 |
| A P50 / P95 / P99, median of per-trial values | 14.6532 / 24.5714 / 32.2224 ms |
| B P50 / P95 / P99, median of per-trial values | 13.8374 / 24.2727 / 32.8943 ms |
| CPU one-core percentage | A loaded primary: 17.06–38.37%; B primaries: 7.59–15.10%; full sys/user deltas retained in raw JSON |
| Redis command delta / instantaneous ops/s | Recorded separately per primary; includes Lua commands and Cluster housekeeping, not business throughput |
| Single-node saturation across load steps/repeated runs | Not established |
| Earlier standalone component baseline | 5,000 successes; 530.52 reservations/s; P99 114.1191 ms; Redis CPU 15.14%; separate run, not the controlled A treatment |

The throughput ratio of medians is +5.30%, but paired B/A changes by round are **+25.12%, +2.28%, +6.75%, −40.78%, +6.88%**. Bucket P99 is worse in four of five rounds; round 4 changes from 31.0644 ms to 69.4471 ms (+123.56%). The observed fluctuations prevent a stable capacity-improvement claim. No cause of those fluctuations was isolated, and neither treatment demonstrates Redis process saturation.

## Decision rule and acceptance boundary

Keep the existing single-activity Lua path: this real controlled A/B does not demonstrate a consistent material gain under single-node saturation. This is a measured non-adoption decision for the current runtime, not a claim that buckets are always slower or never useful. The five-round component comparison is complete, with correctness checks passing. Repeated A/B at one concurrency does not establish a saturation curve, and the balanced exact-demand workload does not establish skew/rebalancing or production Cluster-client readiness.

If future evidence justifies Cluster buckets, use deterministic user-to-bucket routing, retain DB `UNIQUE(activity_id,user_id)` as the global final guard, and keep each Lua operation within one bucket slot. Do not introduce request-level cross-bucket fallback or cross-slot Lua; evaluate background inventory rebalancing with an explicit consistency protocol. Compare repeated trials with documented equal total resources, identical offered load and successful-work mix, errors, tail latency and CPU before adopting. Record skew and exhausted-bucket behavior as well as throughput.

Consequences: avoids unproven production routing, rebalancing and cross-slot complexity today; preserves a reproducible Cluster experiment for the decision. The component results make no claim about HTTP/MQ capacity or end-to-end order throughput.
