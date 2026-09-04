# FlashGuard Stage 0-1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and prove the standalone FlashGuard MySQL baseline while preserving auditable, read-only upstream references.

**Architecture:** A single Spring Boot modular monolith groups code by capability. Controllers translate HTTP, services own transactions and business decisions, MyBatis mappers contain explicit SQL, and MySQL constraints remain the final concurrency boundary.

**Tech Stack:** Java 17, Spring Boot 3.5.16, MyBatis Spring Boot 3.0.5, Flyway, MySQL 8.4.8, JUnit 5, Testcontainers 2.0.5, Maven 3.9.10.

**Spec:** `docs/superpowers/specs/2026-09-04-flashguard-stage-0-1-design.md`

## Global Constraints

- Compile source and bytecode for Java 17 even when Maven runs on JDK 21.
- Use Spring Boot 3.5.16 and MyBatis Spring Boot Starter 3.0.5.
- Use Testcontainers 2.0.5 for real MySQL integration tests.
- Pin MySQL to `mysql:8.4.8`; never use `latest`.
- Keep every directory under `../references/` read-only.
- Use Flyway migrations as the only schema source.
- Keep Redis, RocketMQ, caching, rate limiting, microservices, and circuit breakers out of Stage 1.

---

### Task 1: Upstream provenance and baseline audit

**Files:**
- Create: `REFERENCES.md`
- Create: `docs/architecture/upstream-baseline.md`
- Create: `docs/environment.md`

**Interfaces:**
- Consumes: read-only snapshots under `../references/` and the upstream repository URLs named in the spec.
- Produces: an auditable source inventory and a Stage 0 reading map used by later ADRs.

- [ ] **Step 1: Capture local facts without changing references**

Run each upstream `git ls-remote` or shallow-clone audit outside `../references/`. Compare the shallow checkout against the supplied snapshot while ignoring `.git`, generated artifacts, and local agent metadata. Record whether the exact snapshot SHA is verified or remains unknown.

- [ ] **Step 2: Write `REFERENCES.md`**

Use one entry per upstream with this exact field set:

```markdown
## Goinggoinggoing/seckill

- URL: https://github.com/Goinggoinggoing/seckill
- License: MIT
- Local snapshot: `../references/seckill-main`
- Verified commit: `<40-hex-sha or UNVERIFIED>`
- Verification: `<content match method and date>`
- Used for: V1-V4 evolution and reliability-boundary reading only
- Copied code: none
```

Repeat for `Yukinoshita52/seckill-system` and `wayn111/waynboot-mall`; the second license must be recorded as absent unless the upstream now contains a license that was actually verified.

- [ ] **Step 3: Write the baseline reading map**

Document the real class and method paths for the reference Controller → Service → MySQL/Redis/RocketMQ flow. Separate observations from FlashGuard decisions and explain the V1 MySQL, V2 Redis, V3 MQ, and V4 protection/reconciliation progression.

- [ ] **Step 4: Record the environment**

Record the observed JDK, Maven, Windows, Docker client, Docker Compose, daemon availability, and the exact container/application versions selected for FlashGuard. Never invent CPU, memory, or Docker Engine details that were not observed.

- [ ] **Step 5: Validate and commit Stage 0 documentation**

Run:

```powershell
rg -n "UNVERIFIED|Copied code|License|Verified commit" REFERENCES.md
git diff --check
```

Expected: every source has provenance and copying fields; `UNVERIFIED` appears only when content cannot be tied to a commit; no whitespace errors.

Commit:

```powershell
git add REFERENCES.md docs/architecture/upstream-baseline.md docs/environment.md
git commit -m "docs: add upstream baseline audit"
```

### Task 2: Maven application skeleton

**Files:**
- Create: `.gitattributes`
- Create: `.gitignore`
- Create: `pom.xml`
- Create: `src/main/java/com/flashguard/FlashGuardApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/java/com/flashguard/FlashGuardApplicationTest.java`

**Interfaces:**
- Consumes: version constraints from the spec.
- Produces: executable `com.flashguard:flashguard:0.1.0-SNAPSHOT` and a loadable Spring application context.

- [ ] **Step 1: Write the failing context test**

```java
package com.flashguard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
class FlashGuardApplicationTest {
    @Test
    void applicationContextLoads() {
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `mvn -Dtest=FlashGuardApplicationTest test`

Expected: Maven fails because the project POM and application class do not exist.

- [ ] **Step 3: Add the minimal Maven and application files**

The POM must use the Spring Boot 3.5.16 parent, `<java.version>17</java.version>`, MyBatis 3.0.5, Testcontainers BOM 2.0.5, and dependencies for web, validation, MyBatis, Flyway MySQL, MySQL runtime, starter-test, Testcontainers JUnit, and Testcontainers MySQL.

```java
package com.flashguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FlashGuardApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlashGuardApplication.class, args);
    }
}
```

- [ ] **Step 4: Run the test and verify GREEN**

Run: `mvn -Dtest=FlashGuardApplicationTest test`

Expected: one test passes and Maven reports `BUILD SUCCESS`.

- [ ] **Step 5: Commit the skeleton**

```powershell
git add .gitattributes .gitignore pom.xml src
git commit -m "feat: bootstrap flashguard application"
```

### Task 3: Schema and activity query

**Files:**
- Create: `src/main/resources/db/migration/V1__create_mysql_baseline.sql`
- Create: `src/main/java/com/flashguard/activity/ActivityStatus.java`
- Create: `src/main/java/com/flashguard/activity/ActivityView.java`
- Create: `src/main/java/com/flashguard/activity/ActivityMapper.java`
- Create: `src/main/java/com/flashguard/activity/ActivityQueryService.java`
- Create: `src/main/java/com/flashguard/shared/error/BusinessCode.java`
- Create: `src/main/java/com/flashguard/shared/error/BusinessException.java`
- Test: `src/test/java/com/flashguard/activity/ActivityQueryServiceTest.java`
- Test: `src/test/java/com/flashguard/support/MySqlContainerSupport.java`

**Interfaces:**
- Consumes: `DataSource`, Flyway schema, and activity id.
- Produces: `ActivityQueryService.findById(long): ActivityView` and mapper methods used by participation.

- [ ] **Step 1: Write the failing real-MySQL query test**

Create a reusable Testcontainers base that starts `mysql:8.4.8` and registers JDBC properties with `@DynamicPropertySource`. Insert activity id `1001` and stock `10`, then assert:

```java
ActivityView result = service.findById(1001L);
assertThat(result.id()).isEqualTo(1001L);
assertThat(result.availableStock()).isEqualTo(10);
```

Also assert an unknown id raises `BusinessException` with code `ACTIVITY_NOT_FOUND`.

- [ ] **Step 2: Run the query test and verify RED**

Run: `mvn -Dtest=ActivityQueryServiceTest test`

Expected: compilation fails because the activity API and schema do not exist.

- [ ] **Step 3: Add the migration and minimal query implementation**

The migration creates `activity`, `activity_stock`, and `orders` with the primary, foreign, check, and unique constraints from the spec. The mapper query joins activity to stock by primary key and maps columns explicitly. `BusinessCode` defines every code from the HTTP contract plus `INTERNAL_ERROR` and `VALIDATION_ERROR`; `BusinessException` carries one code and no transport concerns.

- [ ] **Step 4: Run the query test and verify GREEN**

Run: `mvn -Dtest=ActivityQueryServiceTest test`

Expected: Flyway migrates a clean MySQL container and both query branches pass.

- [ ] **Step 5: Commit schema and query**

```powershell
git add src/main/resources/db src/main/java/com/flashguard/activity src/test/java/com/flashguard/activity src/test/java/com/flashguard/support
git commit -m "feat: add activity and stock baseline schema"
```

### Task 4: Business result types and transaction service

**Files:**
- Create: `src/main/java/com/flashguard/shared/api/ApiResponse.java`
- Create: `src/main/java/com/flashguard/shared/id/OrderNumberGenerator.java`
- Create: `src/main/java/com/flashguard/shared/id/TimeRandomOrderNumberGenerator.java`
- Create: `src/main/java/com/flashguard/inventory/ActivityStockMapper.java`
- Create: `src/main/java/com/flashguard/order/OrderRecord.java`
- Create: `src/main/java/com/flashguard/order/OrderMapper.java`
- Create: `src/main/java/com/flashguard/participation/ParticipationCommand.java`
- Create: `src/main/java/com/flashguard/participation/ParticipationOutcome.java`
- Create: `src/main/java/com/flashguard/participation/ParticipationService.java`
- Create: `src/main/java/com/flashguard/participation/ParticipationTransaction.java`
- Test: `src/test/java/com/flashguard/participation/ParticipationServiceTest.java`
- Test: `src/test/java/com/flashguard/shared/id/TimeRandomOrderNumberGeneratorTest.java`

**Interfaces:**
- Consumes: `ParticipationCommand(long activityId, long userId, String requestId)`.
- Produces: `ParticipationOutcome(BusinessCode code, String requestId, String orderNo, String status)`.
- Produces: `OrderNumberGenerator.next(): String`.

- [ ] **Step 1: Write failing unit tests for decisions**

Use fakes for repository boundaries and assert observable outcomes: replay returns the existing order without calling stock deduction; missing/not-started/ended activities raise the exact business code; affected-row count zero raises `SOLD_OUT`; a successful call deducts once and inserts one order; duplicate purchase is mapped after transaction rollback by the public facade.

- [ ] **Step 2: Verify the decision tests fail**

Run: `mvn -Dtest=ParticipationServiceTest,TimeRandomOrderNumberGeneratorTest test`

Expected: compilation fails because the production types are missing.

- [ ] **Step 3: Implement the minimal business flow**

Use a non-transactional `ParticipationService` facade and a separate `ParticipationTransaction` Spring bean with `@Transactional`, so the proxy applies and duplicate-key recovery reads only after rollback. The mapper contract is:

```java
int decrementIfAvailable(long activityId);
Optional<OrderRecord> findByRequestId(String requestId);
Optional<OrderRecord> findByActivityAndUser(long activityId, long userId);
void insert(OrderRecord order);
```

Translate only duplicate-key conflicts after re-reading business facts; let other database errors propagate as system errors.

- [ ] **Step 4: Verify unit tests pass**

Run: `mvn -Dtest=ParticipationServiceTest,TimeRandomOrderNumberGeneratorTest test`

Expected: all named tests pass with no warnings from application code.

- [ ] **Step 5: Commit business behavior**

```powershell
git add src/main/java/com/flashguard/shared src/main/java/com/flashguard/inventory src/main/java/com/flashguard/order src/main/java/com/flashguard/participation src/test/java/com/flashguard/participation src/test/java/com/flashguard/shared
git commit -m "feat: implement atomic mysql participation"
```

### Task 5: Real MySQL concurrency and idempotency

**Files:**
- Test: `src/test/java/com/flashguard/participation/ParticipationMySqlIntegrationTest.java`

**Interfaces:**
- Consumes: the production `ParticipationService` and clean Flyway schema.
- Produces: executable evidence for stock, duplicate-purchase, replay, and transaction invariants.

- [ ] **Step 1: Write the failing replay and duplicate tests**

Against MySQL, assert two calls with the same request id return one order and decrement once. Assert the same user with two request ids creates one order and leaves stock decremented once.

- [ ] **Step 2: Run the focused tests and verify RED**

Before retaining the new tests, temporarily change the transaction flow to bypass the `requestId` lookup. Run the focused replay test and observe a second deduction or duplicate-key failure, then restore the implementation and continue. This mutation proves the integration test catches the regression instead of passing tautologically.

- [ ] **Step 3: Make the database behavior GREEN**

Adjust mapper SQL and transaction boundaries only as required by the failing behaviors. Do not weaken unique constraints or catch generic exceptions.

- [ ] **Step 4: Add and run the 1000-request concurrency test**

Seed stock `100`, use 1000 distinct users, release requests with a `CountDownLatch`, collect outcomes, and assert literal invariants:

```java
assertThat(confirmed).isEqualTo(100);
assertThat(soldOut).isEqualTo(900);
assertThat(orderCount).isEqualTo(100);
assertThat(availableStock).isZero();
assertThat(minimumObservedStock).isGreaterThanOrEqualTo(0);
```

Run: `mvn -Dtest=ParticipationMySqlIntegrationTest test`

Expected: all integration tests pass on `mysql:8.4.8`.

- [ ] **Step 5: Commit concurrency evidence**

```powershell
git add src/test/java/com/flashguard/participation/ParticipationMySqlIntegrationTest.java src/main
git commit -m "test: verify atomic stock update under concurrency"
```

### Task 6: REST API and error translation

**Files:**
- Create: `src/main/java/com/flashguard/activity/ActivityController.java`
- Create: `src/main/java/com/flashguard/participation/ParticipationController.java`
- Create: `src/main/java/com/flashguard/participation/ParticipationRequest.java`
- Create: `src/main/java/com/flashguard/order/OrderController.java`
- Create: `src/main/java/com/flashguard/order/OrderQueryService.java`
- Create: `src/main/java/com/flashguard/shared/error/GlobalExceptionHandler.java`
- Test: `src/test/java/com/flashguard/activity/ActivityControllerTest.java`
- Test: `src/test/java/com/flashguard/participation/ParticipationControllerTest.java`
- Test: `src/test/java/com/flashguard/order/OrderControllerTest.java`

**Interfaces:**
- Consumes: the service interfaces produced by Tasks 3 and 4.
- Produces: the four `/api/v1` endpoints and `{code,message,data}` JSON contract in the spec.

- [ ] **Step 1: Write failing MVC tests**

Use `@WebMvcTest` with real controllers and mocked service boundaries. Assert status, business code, and returned identifiers for success, replay, missing resource, sold out, duplicate purchase, invalid user id, blank request id, and request id longer than 64 characters.

- [ ] **Step 2: Run MVC tests and verify RED**

Run: `mvn -Dtest=ActivityControllerTest,ParticipationControllerTest,OrderControllerTest test`

Expected: compilation fails because controllers and handler are missing.

- [ ] **Step 3: Implement controllers and handler**

Map success to 200/201, missing resources to 404, state conflicts to 409, validation to 400, and unexpected failures to 500. The handler must never include exception class names, SQL, or stack traces in JSON.

- [ ] **Step 4: Run MVC and full tests**

Run:

```powershell
mvn -Dtest=ActivityControllerTest,ParticipationControllerTest,OrderControllerTest test
mvn test
```

Expected: both commands report `BUILD SUCCESS`.

- [ ] **Step 5: Commit the REST contract**

```powershell
git add src/main/java/com/flashguard src/test/java/com/flashguard
git commit -m "feat: expose flashguard baseline api"
```

### Task 7: Runtime packaging, ADRs, and final verification

**Files:**
- Create: `compose.yml`
- Create: `.env.example`
- Create: `README.md`
- Create: `docs/adr/001-use-java17-boot3.md`
- Create: `docs/adr/002-atomic-sql-baseline.md`
- Create: `scripts/check-baseline.ps1`

**Interfaces:**
- Consumes: packaged Spring Boot jar and MySQL schema.
- Produces: reproducible local startup and one-command Stage 1 invariant check.

- [ ] **Step 1: Add pinned MySQL Compose and health check**

Define `mysql:8.4.8`, database `flashguard`, user `flashguard`, environment-substituted passwords, port `${FLASHGUARD_MYSQL_PORT:-3306}`, a named volume, and `mysqladmin ping` health check. Do not embed a production password.

- [ ] **Step 2: Add executable baseline check**

The PowerShell script waits for application health, creates or uses a seeded activity, issues 1000 requests with distinct users, queries final results, and exits nonzero unless orders equal 100 and available stock equals 0. The script must distinguish HTTP business rejects from transport/system errors.

- [ ] **Step 3: Write README and ADRs**

README must include scope/non-goals, architecture, request lifecycle, data invariants, prerequisites, exact start/test commands, four curl examples, and current Stage 1 limitations. ADR 001 records Java/Spring selection and ADR 002 compares select-then-update, locks, optimistic versioning, and the chosen conditional update.

- [ ] **Step 4: Run complete verification**

Run:

```powershell
mvn clean verify
docker compose config
git diff --check
git status --short
```

When the Docker daemon is available, also run:

```powershell
docker compose up -d --wait mysql
mvn spring-boot:run
```

In a second shell run `powershell -File scripts/check-baseline.ps1`, then stop the application and run `docker compose down` without `-v` so the data volume remains recoverable.

- [ ] **Step 5: Commit Stage 1 delivery**

```powershell
git add compose.yml .env.example README.md docs/adr scripts
git commit -m "docs: add mysql baseline quick start"
```

- [ ] **Step 6: Re-read the spec and report exact evidence**

Check each requirement in `docs/superpowers/specs/2026-09-04-flashguard-stage-0-1-design.md` against code, tests, runtime output, and Git history. Report anything not verified as a gap; do not infer completion from a partial test run.
