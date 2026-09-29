# 可复现真实 HTTP 基准

`RealHttpBenchmarkTest` 启动专属 MySQL 8.4.8、Redis 7.4.8 容器，官方 RocketMQ 5.3.2 NameServer/Broker，以及随机 HTTP 端口的实际 Spring 应用。MQ 默认使用本地 Java 17 运行时镜像加载官方 Maven JAR，在 Docker 文件系统运行，限制为 2 CPU、1.5 GiB；环境文件记录实际镜像 ID、Java 版本、磁盘与 JAR 哈希。k6 通过 POST 参加接口，收到 PENDING 后通过 GET 查询到 CONFIRMED 才记为完成。同步 MySQL 路径不预占 Redis，其 Redis 库存检查明确标为 N/A。

前提：Docker Desktop 可用、JDK/Maven 可用、k6 已安装。默认检测 `C:\Program Files\k6\k6.exe`，其他位置使用 `-Dperf.k6=绝对路径`。不能传入外部 `flashguard.test.db.url`，基准拒绝操作共享数据库。

在仓库目录执行（若环境需要 Maven 镜像/代理 settings，保留工作区既有 `-s` 参数）：

```powershell
Set-Location 'D:\code\flashguard-workbench\flashguard'
docker build -t flashguard-mq-test-runtime:local -f config/rocketmq/Dockerfile.test-runtime .
mvn '-Dtest=RealHttpBenchmarkTest' '-Dflashguard.perf.enabled=true' '-Dperf.repetitions=5' '-Dperf.warmup.seconds=60' '-Dperf.seconds=180' '-Dperf.rate=10' '-Dperf.vus=100' test
```

默认 10 个场景，每个场景先预热 60 秒，再重复 5 轮，每轮稳态 180 秒、10 次参加请求/秒，符合方案 9.5 的时间与重复次数要求：MySQL 和 async 的全成功、售罄、固定 50 个 requestId 重放、混合用户流量，以及 async 单机限流和 Redis 全局限流。原有 8 场景已于 2026-09-17 完成正式五轮，见[阶段台账](../docs/validation/2026-09-13/stages-1-8-acceptance.md)；新增 mixed 场景的证据单独记录。限流阈值均为目标速率的一半；它们与相同速率的 async-success 比较。完整默认运行约需 160 分钟流量时间，再加组件启动、偏移提交、结果采集。预热使用独立 activity，原始结果保留在 `warmup-excluded`，不进入中位数结论。首次实机运行前可做功能预检：

```powershell
mvn '-Dtest=RealHttpBenchmarkTest' '-Dflashguard.perf.enabled=true' '-Dperf.scenarios=mysql-success,async-success' '-Dperf.repetitions=1' '-Dperf.warmup.seconds=0' '-Dperf.seconds=5' '-Dperf.rate=10' '-Dperf.vus=30' test
```

预热不足 60 秒、稳态不足 180 秒、少于 5 轮或资源采样缺失均标记 REVIEW，不能作为完成方案要求的基准；每个短缺项写入 `protocolShortfalls`。该测试默认跳过，日常 `mvn test` 不会触发长时间压测。`perf.scenarios` 可选逗号分隔的 `mysql-success,mysql-soldout,mysql-replay,mysql-mixed,async-success,async-soldout,async-replay,async-mixed,async-limiter-local,async-limiter-global`。

`mixed` 固定每两个相邻迭代共享 userId、使用不同 requestId；库存大于全部请求数。每对请求无论谁先到都只能产生一单，另一请求必须返回 HTTP 409 / DUPLICATE_PURCHASE。N 次请求应有 `ceil(N/2)` 张订单及确认响应、`floor(N/2)` 次重复购买拒绝；售罄、限流或任何系统错误均不算预期混合结果。它与 `replay` 的“同 requestId 返回原结果”分别验证，不能把业务拒绝当作订单吞吐。只补跑混合场景：

```powershell
Set-Location 'D:\code\flashguard-workbench\flashguard'
mvn '-Dtest=RealHttpBenchmarkTest' '-Dflashguard.perf.enabled=true' '-Dperf.scenarios=mysql-mixed,async-mixed' '-Dperf.warmup.seconds=60' '-Dperf.seconds=180' '-Dperf.repetitions=5' '-Dperf.rate=10' '-Dperf.vus=100' test
```

`scripts/run-benchmarks.ps1`、`scripts/verify-real-chain.ps1` 和 `scripts/verify-stage5.ps1` 会在项目目录运行 Maven，并恢复调用目录。工作台 settings 存在时沿用；独立克隆不存在该文件时使用 Maven 默认配置，也可以显式传入 `-MavenSettings 'C:\path\settings.xml'`。纯脚本契约验证可运行 `node --test benchmark/k6/http-full-path.test.cjs` 和 `pwsh -NoProfile -File scripts/tests/benchmark-wrappers.test.ps1`，它们不替代真实 HTTP 验收。

Windows 长测需接通电源并保持开盖。可选包装器 `pwsh -NoProfile -File scripts/run-awake-validation.ps1 -Mode AsyncMixed` 只补跑异步混合五轮，`-Mode Regression` 执行全量验证，`-Mode Probe` 仅验证电源请求。它要求 64 位 PowerShell 7 和交流电，运行期间持有临时 system/execution 请求，结束时释放，不更改永久电源方案或锁屏策略。主动睡眠/合盖仍会中断实验；电池模式下临时请求受系统限制，见 [Microsoft PowerSetRequest](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-powersetrequest)。2026-09-28 因 Modern Standby 中断的失败轮次已保留，不能删去或计入正常容量统计。

每次执行保留 `benchmark/results/<git-sha>/<timestamp-id>/<scenario>/run-01..05/`：

- `raw.json`：k6 原始 NDJSON 时间序列；`summary.json`：P50/P95/P99 等汇总；`k6.log`：包括 VU 和阈值诊断。
- 汇总字段按类型严格读取：Counter 必须有数值 `count`，Rate 的比例必须读取数值 `value`（当前 k6 `summary-export` 格式），Trend 必须有数值 `med`、`p(95)`、`p(99)`。Counter 的 `rate` 是每秒计数，不能拿它代替 Rate 的 `value`；字段缺失、非数值或非有限数值直接报错，不默认视为零错误。
- `config.json`、`invariants.json`：实际库存、订单、重复订单、终态预占数、Broker 提交消息数量/消费积压/DLQ、业务拒绝、系统错误、丢弃迭代与判定。
- `resources.ndjson` 与带时间戳的 `prometheus-*.txt`：运行期间每次采集结束后间隔 5 秒，记录专属 MySQL/Redis/MQ 容器 CPU/内存，以及应用 CPU 累积时间、堆内存与指标；并保留前后快照和 `redis-info.txt`。离散采样不代表捕获了采样间隔内的瞬时峰值。
- 执行根目录 `environment.json`、`working-tree.patch`、`broker.conf`：宿主机、JVM、精确组件版本、Docker HostConfig 资源限制、脚本/工作区源文件 SHA-256；包含未跟踪源码的 sourceTreeSha256。
- `aggregate.json`：保留全部测量轮次，汇总吞吐及入口/最终确认 P50/P95/P99 的轮间中位数、min/max。实际落库订单 RPS 的分母包含负载启动与最终收敛时间，同时记录按配置稳态窗口计算的数值。重放场景单独统计响应完成数与唯一落库订单数，不把重放响应当作新订单。

每轮使用新 activity/request 命名空间，数据只写专属容器；同一应用配置内复用 Topic，在收敛后继续，记录实际 Broker 累积偏移差。此方式不需要删除 Broker 文件。性能环境事务超时为 6000ms、回查间隔为 5000ms，与 Compose 一致，避免故障测试加速回查参数改变性能结果。每轮要求库存非负、订单不超库存、一人至多一单、预占全部终态、异步 Redis/DB 库存相等、MQ 积压为零且无 DLQ。Broker 消息少于订单为 FAIL；额外消息数量单独记录，非重放场景有未归因额外消息时为 REVIEW，不将其直接视为已确认重复消息。业务正确性 `technicalStatus` 与完整基准 `benchmarkStatus` 分开记录。

任何系统错误、`dropped_iterations>0`、实际到达不足目标 99% 或不变量失败均为 FAIL，原始数据保存后停止后续轮次。预热失败同样停止正式测量。`aggregate.json` 总结论尊重每轮 REVIEW/FAIL；未执行完的套件不能为 PASS。全成功场景还要求无业务拒绝且每个请求最终得到确认订单。

不变量分别记录 `stockConservationPassed`、`uniquenessPassed`、`orderResponseAccountingPassed`、`reservationsTerminalPassed`，避免将“请求超时但订单已提交”的响应记账差异误称为库存破坏。早期派生 JSON 中错误率误读的修复保留 `derivedMetricCorrection` 注记、原值和原始文件哈希；不修改 k6 原始数据或原始汇总。

测量期间 `flashguard.scheduling.enabled=false`，环境文件明确记录 `schedulingEnabled=false`。因此负载不包含定时补偿、孤儿预占恢复与对账任务；恢复正确性由独立真实故障测试验证。这些结果不能称为包含全部后台工作的生产总容量。

该结果只证明选定速率下的表现；没有自动进行饱和点搜索。提高 `perf.rate` 需重复完整协议，在错误率或资源持续饱和时停止升档。它不等同于百万 HTTP 请求、百万历史订单查询或 Redis Cluster 分桶实验，不能据此填写那些实验的结论。

