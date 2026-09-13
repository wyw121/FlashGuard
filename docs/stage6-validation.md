# Stage 6 cache and rate-limit validation

Run in a PowerShell terminal with Java 17, Maven and Docker available. Use the repository Maven settings if this machine has the known global proxy issue. No application process or MQ is required for these focused component tests.

```powershell
Set-Location 'D:\code\flashguard-workbench\flashguard'
mvn '-Dtest=ActivityCacheServiceTest,ActivityCacheConcurrencyTest,ParticipationRateLimiterTest' test
mvn '-Dtest=ActivityCacheRedisTest,ParticipationRateLimiterRedisTest' test
Get-Content 'D:\code\flashguard-workbench\flashguard\target\stage6-rate-limit-overhead.txt'
```

If the project Maven profile excludes container tests by default, add `-PrealRedis` to the second command. Real Redis tests use `redis:7.4.8` through RedisContainerSupport; `-Dflashguard.test.redis.image=...` can choose a locally available pinned image and must be recorded with results.

Pass criteria:

- Concurrent local cache readers cause one MySQL mapper load; repeated missing IDs are cached; local expiry reloads.
- Redis outage falls back to DB and local cache. Independent cache instances share positive and negative snapshots through real Redis; Redis expiration allows a fresh DB read.
- Local user scope spans activities, activity/user rejections consume neither budget, exact window expiry permits admission, and concurrent cleanup/admission never exceeds the configured count.
- Independent global limiter instances share both budgets; Redis expiration permits admission; concurrent Lua calls admit exactly the configured activity count.
- The overhead test prints and saves 500 sequential allowed calls per mode after 100 warmup calls, with local/global median and p95 microseconds. No latency threshold is asserted: this is a reproducible environment observation, not a throughput benchmark. Save the generated file with the environment details when recording evidence.

Configuration defaults: activity cache local TTL 3000 ms, Redis TTL 15000 ms, maximum-size 10000; limiter enabled true, global-enabled false, activity limit 120/window-ms 1000, user limit 5/window-ms 1000. Local TTL must be positive and strictly less than Redis TTL. See ADR 006 for stale-read, single-slot, outage and memory boundaries.

HTTP acceptance additionally requires the existing participation controller/error-handler tests and an ingress load run showing 429/THROTTLED at the configured boundary. These component tests do not establish that DB/MQ remains within capacity under production traffic.
