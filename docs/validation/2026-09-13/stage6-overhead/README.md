# Retained limiter call-overhead observation

The [original text](stage6-rate-limit-overhead.txt) was copied unchanged from `target/stage6-rate-limit-overhead.txt`. Its SHA-256 and the filesystem timestamp observed during archiving are recorded in [provenance.json](provenance.json).

| Mode | Median, microseconds | P95, microseconds |
| --- | --- | --- |
| Local | 13.800 | 38.300 |
| Redis | 5,893.300 | 13,635.200 |

The original file states 500 sequential allowed calls per mode after 100 warmup calls per mode. These are client-observed per-call timings, not concurrent throughput or HTTP/DB/MQ protection results.

**Historical provenance is incomplete.** The text contains no execution command, Redis version/digest, client environment, resource limits, execution Git revision or dirty-source hash, and no contemporaneous manifest accompanied it. The observed last-write timestamp does not independently prove when the test ran. This archive deliberately leaves those fields unknown; later HTTP/Cluster environments must not be attached to this earlier observation.

This preserves an existing measurement for audit and avoids losing it when `target` is rebuilt. It is insufficient as a fully reproducible performance result without a new run that captures its own environment/source manifest. Current reproduction guidance is in [stage6-validation.md](../../../stage6-validation.md); that guide is not evidence of the historical command.
