# FlashGuard Stage 2 Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans. User has authorized continuing the implementation book.

**Goal:** Implement and prove the Redis Lua reservation component without claiming an asynchronous order exists.

**Architecture:** Explicit MySQL snapshot warmup, one Redis Lua atomic admission operation per activity, and retrievable PENDING state. HTTP/MQ integration follows in Stage 3.

**Tech Stack:** Java 17, Spring Boot 3.5.16, Spring Data Redis managed by Boot, Testcontainers 2.0.5, redis:7.4.8.

**Spec:** docs/superpowers/specs/2026-09-06-flashguard-stage-2-design.md

## Global constraints

Preserve MySQL baseline; references read-only; no global machine changes; explicit version pins; no request-key TTL; no claimed throughput without measurements. All writes stay in this repository. Work on the clean feature branch created from c115bfa.

### Task 1: Atomic reservation and warmup component

Files: pom.xml; src/main/resources/application.yml; src/main/resources/redis/{warmup_stock,reserve_stock}.lua; src/main/java/com/flashguard/reservation/*.java; src/test/java/com/flashguard/reservation/*Test.java; src/test/java/com/flashguard/support/RedisContainerSupport.java; compose.yml; .env.example.

Interfaces: `RedisReservationStore.warmUp(ActivityView)`, `reserve(long activityId, long userId, String requestId)`, `findByRequestId(long activityId, String requestId)`; `ReservationWarmupService.warmUp(long activityId)`. Typed records/enums for results; exact signatures may refine these names while preserving semantics.

- [x] Write real Redis integration acceptance tests plus warmup service tests; capture RED.
- [x] Implement typed state, two scripts and Spring adapter; use string IDs and Redis TIME.
- [x] Add opt-in Compose redis profile with healthcheck, AOF and noeviction, env/config timeouts.
- [x] Run targeted tests, inspect invariants and actual server version; capture GREEN: 19/19 on Redis 7.4.8.
- [x] Review component independently, fix both substantive findings, rerun covering tests; scoped re-review passed.

### Task 2: Evidence and reproduction documentation

Files: docs/adr/003-redis-lua-reservation.md; docs/architecture/redis-reservation.md; docs/environment.md; README.md; docs/validation/2026-09-06-stage-2.md.

- [x] Document key schema, ownership, TTL/expireAt, warmup safeguards, replay scope and Stage 3/5 boundary.
- [x] Record stage1 target-container retry and real stage2 tests, with exact command and image version.
- [x] Update README stage status and commands; 51 tests passed, packaging passed after releasing a test-helper JAR lock, and git diff --check passed.
- [x] Final independent review passed; tested changes retained on feature branch. Evidence: docs/validation/2026-09-06-stage-2.md.
