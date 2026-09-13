# HTTP calibration: preserve failed preflights, 2026-09-13

**These are calibration runs, not sustainable-capacity results.** The latest five asynchronous scenarios pass their short-run technical gates after the response-state correction; their formal benchmark status remains REVIEW. Earlier failed cold preflights are retained below. None meets the formal protocol of 60 seconds of excluded warmup, 180 seconds of steady load and five measured repetitions. Formal HTTP results remain pending.

## Latest calibration after terminal-response correction

Run `2026-09-13T13-31-11.516830900Z-29d1ecf6` completed all five asynchronous scenarios. Its [aggregate](../../../benchmark/results/ece8209ec152395a9a092a214b7eafab443288ed/2026-09-13T13-31-11.516830900Z-29d1ecf6/aggregate.json) records technical PASS for every scenario and overall **REVIEW**. The [environment](../../../benchmark/results/ece8209ec152395a9a092a214b7eafab443288ed/2026-09-13T13-31-11.516830900Z-29d1ecf6/environment.json) records 5 seconds of warmup, 10 seconds of measured traffic, one repetition, 10 offered requests/s and 100 VUs; each scenario retains its own raw `summary.json`, k6 log and inventory results. Scheduled recovery/reconciliation is disabled during this load experiment.

| Scenario | Attempts | Client confirmations | Expected business rejections | Unique orders | Initial / final DB stock | Admission P99 ms | Observed confirmation P99 ms |
| --- | --- | --- | --- | --- | --- | --- | --- |
| async-success | 100 | 100 | 0 | 100 | 200 / 100 | 364.3952 | 594.31 |
| async-soldout | 101 | 2 | 99 sold out | 2 | 2 / 0 | 168.9539 | 295.93 |
| async-replay | 100 | 100 | 0 | 50 | 200 / 150 | 303.1391 | 471.41 |
| async-limiter-local | 101 | 48 | 53 throttled | 48 | 200 / 152 | 197.0563 | 363.56 |
| async-limiter-global | 101 | 50 | 51 throttled | 50 | 200 / 150 | 283.4706 | 509.85 |

All five scenarios report zero raw system-error rate, zero dropped iterations, zero duplicate orders, zero nonterminal reservations, zero final Broker lag and zero DLQ messages. Redis available stock matches final DB available stock in each scenario. The replay case intentionally returns 100 confirmed responses for 50 unique orders; the rejection cases intentionally produce fewer orders than attempts. Broker offset deltas show zero additional messages for these measured windows, without claiming general exactly-once delivery.

The retained application log `D:\code\flashguard-workbench\work-notes\http-calibration-terminal-fix.log` records **27 tests passed: 26 ParticipationService unit tests and one RealHttpBenchmark test containing the five scenarios**; Maven reports BUILD SUCCESS. This is separate from the previously verified 20/20 concurrent local-transaction regression. The terminal-response correction has short-run HTTP evidence, but neither these 27 tests nor the earlier regression replaces final full-suite verification or formal load acceptance.

This run still uses dirty base revision `ece8209ec152395a9a092a214b7eafab443288ed`; its captured source-tree SHA-256 is `3deda41978c8ebedf6991dc0e23258e8185551b5c3835ab3db1610c9f46199eb`, and k6 script SHA-256 is `f5859a89bf17f87a1d484ab3074e7d254a5274246555f33a0c1649f7ea91a379`. Do not substitute this source identity for earlier failed runs. The observations establish behavior at this short configured load, not maximum or sustainable capacity.

## Historical cold-run identity and protocol

All three cases below use base Git revision `ece8209ec152395a9a092a214b7eafab443288ed` with **uncommitted working-tree changes**. The run directories preserve environment metadata, source-tree/script hashes and the tracked working-tree diff. These are not results of the base commit alone.

| Run directory suffix | Scenarios completed | Offered rate / duration / VUs | Warmup / repetitions | Source tree SHA-256 |
| --- | --- | --- | --- | --- |
| `2026-09-13T12-43-53.612484100Z-164a270e` | MySQL success; async success | 10 requests/s / 5 s / 30 | 0 s / 1 | `66af1165281ee39e81e88ec3f687edbd6f5a83df9a83b215a14a2273d3660ac4` |
| `2026-09-13T13-03-38.943700200Z-961db2e0` | MySQL success only | 50 requests/s / 10 s / 200 | 0 s / 1 | `f64b281c16fd65c128631a554c213d3e797e125113df0e5d43ba3a3e60ae3234` |

See the [10/s environment](../../../benchmark/results/ece8209ec152395a9a092a214b7eafab443288ed/2026-09-13T12-43-53.612484100Z-164a270e/environment.json) and [50/s environment](../../../benchmark/results/ece8209ec152395a9a092a214b7eafab443288ed/2026-09-13T13-03-38.943700200Z-961db2e0/environment.json). Both use Windows 11, Java 21.0.9, MySQL 8.4.8, Redis 7.4.8 and a 20-connection DB pool. The later environment explicitly disables scheduled compensation/orphan/reconciliation jobs during measurement; its results therefore also exclude that production background workload. The host is shared, not a dedicated load-test machine.

## Exact outcomes from raw k6 and persisted snapshots

| Metric | MySQL 10/s smoke | Async 10/s smoke | MySQL 50/s cold calibration |
| --- | --- | --- | --- |
| Participation POST attempts | 50 | 51 | 411 |
| Client-accepted responses | 50 | 48 | 389 |
| Client-observed confirmed results | 50 | 41 | 389 |
| System-error samples / attempts | 0 / 50 | 10 / 51 | 22 / 411 |
| Raw system-error rate | 0% | 19.607843% | 5.352798% |
| Dropped iterations | 0 | 0 | 89 |
| Final persisted orders | 50 | 48 | 411 |
| Initial / final DB stock | 150 / 100 | 150 / 102 | 600 / 189 |
| Duplicate orders | 0 | 0 | 0 |
| k6 exit code | 0 | 99 | 99 |
| Technical conclusion | PASS for this short smoke | FAIL | FAIL |
| Formal benchmark conclusion | REVIEW: protocol incomplete | FAIL; protocol also incomplete | FAIL; protocol also incomplete |

Sources: MySQL 10/s [raw summary](../../../benchmark/results/ece8209ec152395a9a092a214b7eafab443288ed/2026-09-13T12-43-53.612484100Z-164a270e/mysql-success/run-01/summary.json) and [DB snapshot](../../../benchmark/results/ece8209ec152395a9a092a214b7eafab443288ed/2026-09-13T12-43-53.612484100Z-164a270e/mysql-success/run-01/invariants.json); async 10/s [raw summary](../../../benchmark/results/ece8209ec152395a9a092a214b7eafab443288ed/2026-09-13T12-43-53.612484100Z-164a270e/async-success/run-01/summary.json) and [DB snapshot](../../../benchmark/results/ece8209ec152395a9a092a214b7eafab443288ed/2026-09-13T12-43-53.612484100Z-164a270e/async-success/run-01/invariants.json); MySQL 50/s [raw summary](../../../benchmark/results/ece8209ec152395a9a092a214b7eafab443288ed/2026-09-13T13-03-38.943700200Z-961db2e0/mysql-success/run-01/summary.json) and [DB snapshot](../../../benchmark/results/ece8209ec152395a9a092a214b7eafab443288ed/2026-09-13T13-03-38.943700200Z-961db2e0/mysql-success/run-01/invariants.json).

The async run's 272 total HTTP requests include result polling; **51**, not 272, is the business-attempt denominator. Its ten error samples are not ten proven deadlocks: application logs show three local-callback insert deadlocks, while 48 requests were accepted and only 41 confirmations were observed by the client. The raw results alone do not assign a common cause to the other seven incomplete observations.

## Latency and load-delivery limits

| Metric, ms | MySQL 10/s | Async 10/s | MySQL 50/s |
| --- | --- | --- | --- |
| Admission P50 | 89.3328 | 690.1927 | 5,338.0142 |
| Admission P95 | 440.3051 | 1,472.5409 | 9,994.6156 |
| Admission P99 | 496.0537 | 2,792.2701 | 10,001.5836 |
| Observed confirmation P50 | 90.5 | 1,862 | 5,095 |
| Observed confirmation P95 | 443.5 | 2,208 | 8,485.4 |
| Observed confirmation P99 | 502.31 | 2,344.8 | 9,159.8 |

Confirmation latency includes only the clients that observed confirmation: 50, 41 and 389 samples respectively. It excludes the failed/incomplete observations and must not be presented as latency for every submitted request.

At 50/s, the target window called for 500 iterations; 411 ran and 89 were dropped, a **17.8% offered-load delivery shortfall**. The [k6 log](../../../benchmark/results/ece8209ec152395a9a092a214b7eafab443288ed/2026-09-13T13-03-38.943700200Z-961db2e0/mysql-success/run-01/k6.log) records the 200-VU ceiling and 22 POST request timeouts at the configured 10-second HTTP deadline. Both dropped-iteration and system-error thresholds failed. This is not a successful 50 requests/s result.

The values 41.1 submitted requests/s and 38.9 observed confirmations/s divide totals by the configured ten-second arrival window. Requests continued completing after that window; the raw k6 summary reports approximately 25.55 POST requests/s and 24.19 observed confirmations/s over its longer reporting interval. None of these denominators establishes sustainable capacity from one cold ten-second run.

The final 50/s DB snapshot contains **411 orders despite only 389 client confirmations**. Together with 22 timeout responses, this shows why response loss must not be treated as proof of transaction rollback. The stock arithmetic holds: `600 = 411 + 189`, and duplicate orders are zero. The original collector also recorded a DB/order convergence gate failure based on its client-confirmation comparison; that label must not be interpreted as arithmetic stock loss or overselling. Client completion and offered-load gates still fail even though the persisted stock/order snapshot conserves inventory.

## Rate-parser correction

The earlier derived `systemErrorRate=0.0` field in affected `invariants.json` / aggregate output was a **parser defect**, not a clean result. This k6 summary serializes a Rate metric under `metrics.system_error_rate.value`. The corrected collector reads that field in [RealHttpBenchmarkTest](../../../src/test/java/com/flashguard/perf/RealHttpBenchmarkTest.java) and records `systemErrorRateSource=summary.metrics.system_error_rate.value`.

This document uses the immutable raw values directly:

```text
Async 10/s: metrics.system_error_rate.value = 0.19607843137254902 = 10 / 51
MySQL 50/s: metrics.system_error_rate.value = 0.0535279805352798 = 22 / 411
```

The associated [async k6 log](../../../benchmark/results/ece8209ec152395a9a092a214b7eafab443288ed/2026-09-13T12-43-53.612484100Z-164a270e/async-success/run-01/k6.log) prints 19.60%, and the 50/s log prints 5.35%; both exit 99. Repairing derived summaries does not rerun the load or turn either failed calibration into PASS. Raw summaries/logs remain authoritative while derived artifacts are corrected separately.

## Deadlock correction and what its checks establish

The historical local application log at `D:\code\flashguard-workbench\work-notes\mq-and-http-preflight-retry.log` contains three `StockReservationMapper.insert` deadlock traces beginning around lines 2563, 2685 and 2807. In that same combined invocation, `RocketMqIntegrationTest` passed all 11 tests, while `RealHttpBenchmarkTest` failed. Standalone MQ success did not establish HTTP load acceptance.

The missing-row locking reads in local transaction/fence creation were subsequently changed to `READ_COMMITTED`, preserving existing-row locks and unique ownership constraints. The current real MySQL/Redis regression `ReservationCompensationIntegrationTest#twentyDistinctConcurrentLocalTransactionsCommitWithoutDeadlocks` passed with `attempts=20 failures=0`, 20 `MQ_COMMITTED` records, 20 Redis pending reservations, no orders and no cancellation fences. The latest available suite report records 13 compensation integration tests passed; `ReservationTransactionListenerTest` records 12 tests passed. These reports are under `target/surefire-reports` and may be overwritten by later runs.

Those checks verify the targeted transaction behavior. The newer short asynchronous calibration is reported at the top of this document; neither result explains the historical synchronous MySQL 50/s timeouts or completes formal load acceptance. Preserve both failed calibrations as diagnostic evidence; complete the formal 60/180/five-round protocol and its client, inventory, MQ and load-delivery gates before publishing a sustainable-capacity number.
