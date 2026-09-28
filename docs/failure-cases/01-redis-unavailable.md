# 01：专属 Redis 暂停与超时命令恢复

状态：**PASS（2026-09-13 对应真实 MQ 方法）**。见[结果摘要](../validation/2026-09-13/real-mq/results.json)与[保留观测](../validation/2026-09-13/real-mq/fault-observations.txt)：本次 `delayedReservation=true stock=200`，覆盖了晚到预占分支。不代表全项目或性能验收。

前提：Docker Desktop Linux engine 已运行，Java 17+、Maven、PowerShell 7 可用。Maven settings 为可选，工作台已有配置会自动沿用。包装器负责隔离组件、测试活动与 MQ 客户端，不需要启动现有应用或准备活动 1001。

```powershell
pwsh -NoProfile -File 'D:\code\flashguard-workbench\flashguard\scripts\failure-cases\inject-redis-unavailable.ps1'
```

映射到 `RocketMqIntegrationTest#pausedOwnedRedisFailsClosedWithoutDurableOrder`。

测试先预热本测试活动的 200 单位库存，创建真实 MQ Publisher，并暂停**本次测试创建的 Redis 容器**。async 参加请求使用短预占超时，必须返回 SERVICE_UNAVAILABLE；失败时无 durable reservation、无订单，不回退到 MySQL 直接下单。

恢复在 `finally` 中解除暂停。Redis 超时并不保证命令未执行：测试在共享 Lettuce 连接后续发送 PING，随后检查同 requestId 是否出现晚到预占。若存在，等它过期并调用真实 orphan scanner，使其形成 durable CANCELLED；若不存在，则不伪造一条预占。最后断言无订单，Redis 与 MySQL 可用库存均为 200。

关注输出 `FLASHGUARD_REDIS_OUTAGE delayedReservation=... stock=...`。`false` 只说明该次没观察到晚到分支，不能声称每次都注入了晚到执行；`true` 分支必须同时保留取消与库存恢复证据。

查看 `D:\code\flashguard-workbench\flashguard\target\surefire-reports\` 的当前方法报告，以及 `D:\code\flashguard-workbench\flashguard\target\rocketmq-it\` 日志。本次该方法断言全部通过；后续重跑应按新报告重新判定。该案例覆盖暂停/超时与恢复，不覆盖 Redis 数据丢失或主从切换。
