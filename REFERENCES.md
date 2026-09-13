# FlashGuard Reference Inventory

Audit date: 2026-09-04

FlashGuard is an independent implementation. The directories under `../references/` are read-only learning snapshots; they are not Git submodules and none of their code is compiled into this project.

## Goinggoinggoing/seckill

- URL: https://github.com/Goinggoinggoing/seckill
- License: MIT (`../references/seckill-main/LICENSE`)
- Local snapshot: `../references/seckill-main`
- Local fingerprint: `ba2272b97e137afc4284926cea128525283c0f7e82332b1de14c6466adaf4faf` (SHA-256 over sorted `relative-path<TAB>file-sha256` rows, 61 files)
- Verified commit: `fbe7940cd45b579cd6a9a93713145d31af5cf66a`
- Verification: a 2026-09-04 depth-1 checkout contained the same 61 paths and the same file contents after normalizing CRLF/LF; the only byte-level difference was line endings in `LICENSE`
- Used for: reading the V1 MySQL, V2 Redis/thread-pool, V3 RocketMQ, and V4 protection/reconciliation evolution
- Copied code: none

## Yukinoshita52/seckill-system

- URL: https://github.com/Yukinoshita52/seckill-system
- License: no license file was present in either the supplied snapshot or the verified upstream checkout; ideas may be studied, but code must not be copied
- Local snapshot: `../references/seckill-system-master`
- Local fingerprint: `f60bb6a60ce72e397c0a2b2e539cd8fc36b6939e80a12455fd84988dd2dad723` (SHA-256 over sorted `relative-path<TAB>file-sha256` rows, 179 files)
- Verified commit: `d6bbbd5d18eaeee57cc7d9f2b02ede81736c4318`
- Verification: a 2026-09-04 depth-1 checkout contained the same 179 paths and the same contents after normalizing CRLF/LF; eight files differed only by checkout line endings
- Used for: reading Redis Lua stock reservation, stock buckets, gateway/rate-limiting, cache, consumer retry, and DLQ boundaries
- Copied code: none

## wayn111/waynboot-mall

- URL: https://github.com/wayn111/waynboot-mall
- License: MIT (`../references/waynboot-mall-master/LICENSE`)
- Local snapshot: `../references/waynboot-mall-master`
- Local fingerprint: `f921a0050a86324c32a8f513b531be4e12be80027b35ea64969a944230e6000e` (SHA-256 over sorted `relative-path<TAB>file-sha256` rows, 806 files)
- Verified commit: `UNVERIFIED`
- Upstream HEAD observed during audit: `c683afd8a22c92c298e0258bce134617da3f782d`
- Verification: the depth-1 partial clone obtained the commit reference, but GitHub transfer failed while fetching large blobs; the local 806-file snapshot therefore cannot be claimed as an exact checkout of that commit
- Used for: reading stock freeze/release, order-submit orchestration, delayed-message, idempotent-consumer, and inventory-reconciliation patterns
- Copied code: none

## Apache RocketMQ Spring

- URL: https://github.com/apache/rocketmq-spring
- License: Apache-2.0
- Starter version examined during initial planning: `2.3.6`; not used in the final integration
- Verification: Maven Central artifact metadata, checked 2026-09-04
- Used for: official transaction-message and Spring Boot integration contracts
- Copied code: none

## Independence rules

Current implementation uses the official `rocketmq-client` and `rocketmq-tools` 5.3.2 APIs directly, with explicit Spring beans and transaction proxies (ADR 008). The test runtime also uses official `rocketmq-broker`/`rocketmq-namesrv` 5.3.2 artifacts. Exact JAR hashes and runtime environment are retained with the experiments; no reference implementation's throughput figures are reused.

1. New behavior is specified by FlashGuard tests before implementation.
2. Reference class names, package structure, SQL, Lua scripts, and performance claims are not copied.
3. Any future direct reuse must be listed here with its license and exact source location before it enters the repository.
4. Performance numbers in reference READMEs are untrusted context, not FlashGuard evidence.
