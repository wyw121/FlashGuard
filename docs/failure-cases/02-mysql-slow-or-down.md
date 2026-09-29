# 02：专属 MySQL 暂停、真实 Broker 重投与恢复

状态：**PASS（2026-09-13 对应真实 MQ 方法）**。见[结果摘要](../validation/2026-09-13/real-mq/results.json)与[保留观测](../validation/2026-09-13/real-mq/fault-observations.txt)：本次消费失败 3 次、失败的 Broker 重投 2 次、恢复后成功重投 1 次。不代表全项目或性能验收。

前提：Docker Desktop Linux engine、Java 17+、Maven、PowerShell 7；Maven settings 为可选，工作台已有配置会自动沿用。无需已有应用、活动或外部数据库；测试拒绝外部 MySQL 覆盖配置。

```powershell
pwsh -NoProfile -File 'D:\code\flashguard-workbench\flashguard\scripts\failure-cases\inject-mysql-slow-or-down.ps1'
```

映射到 `RocketMqIntegrationTest#pausedOwnedMysqlCausesBrokerRetryThenRecovers`。脚本名保留 `slow-or-down`，当前实际注入是**暂停专属 MySQL 容器造成不可用/超时**，没有标定延迟毫秒数的慢查询实验。

先通过真实事务消息持久化一个 MQ_COMMITTED 预占，再暂停该容器并启动真实 Consumer。生产消费适配器因 JDBC 不可用返回 RECONSUME_LATER。测试保持暂停，直到首次失败和至少一次 `reconsumeTimes > 0` 的 Broker 重投也失败；不会在消费刚开始前就恢复数据库、把未发生失败的路径算作重试验证。

`finally` 解除暂停后，继续等待实际 Broker 重投成功。此场景给 Consumer 配置 20 次重试上限，为暂态故障恢复留出窗口。最终要求 reservation=CONFIRMED，同 reservation 恰好一条订单，MySQL 与 Redis 库存均从 200 变为 199。

保留 `FLASHGUARD_MYSQL_RETRY failures=... failedBrokerRetries=... successfulBrokerRetries=...` 与 `D:\code\flashguard-workbench\flashguard\target\surefire-reports\` 报告；三个计数的实际值由运行决定。Broker/客户端日志在 `D:\code\flashguard-workbench\flashguard\target\rocketmq-it\`。

该场景证明已发布任务在真实 DB 故障、Broker 重投和恢复后只落一单；不代表杀掉正在 JDBC commit 的进程，也不覆盖 MySQL 磁盘损坏或主从切换。本次该方法已通过；后续重跑应按新报告重新判定。
