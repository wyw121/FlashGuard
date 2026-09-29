# 故障案例：专属组件中的真实链路验证

状态：**PASS（2026-09-13 真实 MQ 故障套件范围）**。保留的 [JUnit 报告](../validation/2026-09-13/real-mq/junit-result.txt) 为 11 项、0 失败、0 错误、0 跳过，耗时约 357.7 秒；[结果摘要](../validation/2026-09-13/real-mq/results.json)列出实际方法与运行环境。这不等于全项目 verify、全部方案或性能容量验收通过。

六个脚本都是 `scripts/verify-real-chain.ps1` 的场景包装器。它们运行 `RocketMqIntegrationTest` 的指定方法，再执行 Maven `verify` 的后续阶段。测试自行创建 MySQL 8.4.8、Redis 7.4.8 和真实 RocketMQ 5.3.2 NameServer/Broker 的隔离环境，创建随机 Topic、消费组和测试活动；不需要已有应用进程、活动 1001 或手工调用管理接口，也不会暂停工作区日常使用的数据库/Redis 容器。

## 前提与入口

需要 Docker Desktop **Linux engine** 已运行、Java 17 或更高版本、Maven、PowerShell 7；Maven settings 为可选，工作台已有配置会自动沿用。首次运行需要下载依赖和镜像；包装器会检查并按需构建 `flashguard-mq-test-runtime:local`。Broker 在专属 Linux 容器中运行官方 Maven 构件，使用自己的存储目录。测试拒绝外部 MySQL 覆盖地址，包装器显式清空该覆盖参数。

按场景选一个命令，建议串行运行，避免宿主资源竞争：

```powershell
pwsh -NoProfile -File 'D:\code\flashguard-workbench\flashguard\scripts\failure-cases\inject-redis-unavailable.ps1'
pwsh -NoProfile -File 'D:\code\flashguard-workbench\flashguard\scripts\failure-cases\inject-mysql-slow-or-down.ps1'
pwsh -NoProfile -File 'D:\code\flashguard-workbench\flashguard\scripts\failure-cases\inject-producer-kill.ps1'
pwsh -NoProfile -File 'D:\code\flashguard-workbench\flashguard\scripts\failure-cases\inject-consumer-kill.ps1'
pwsh -NoProfile -File 'D:\code\flashguard-workbench\flashguard\scripts\failure-cases\inject-consumer-forced-exception.ps1'
pwsh -NoProfile -File 'D:\code\flashguard-workbench\flashguard\scripts\failure-cases\inject-message-backlog.ps1'
```

| 场景 | 实际注入与恢复 | 主要断言 |
| --- | --- | --- |
| [Redis 不可用](01-redis-unavailable.md) | 暂停专属 Redis；async 调用失败后恢复；若超时命令晚到，执行 orphan scanner | SERVICE_UNAVAILABLE，无订单，最终 Redis/DB 库存均恢复 200 |
| [MySQL 不可用](02-mysql-slow-or-down.md) | 暂停专属 MySQL，观察失败 Broker 重投，再恢复并成功重投 | 真实失败/成功重投均出现；一单、两侧库存 199 |
| [Producer 退出](03-producer-kill.md) | 子 JVM 在 half message 后、本地事务前，或本地提交后 `halt(137)`；当前脚本在其退出后启动同组新 Producer 冷恢复 | 前者取消且零单，后者确认且一单，回查次数大于零 |
| [Consumer 退出](04-consumer-kill.md) | 子 JVM 收到消息、尚未调用 JDBC 业务事务时 `halt(137)`；同组新 Consumer 接管 | 退出时零单，恢复后一单，库存只扣一次 |
| [消费者持续失败](05-consumer-forced-exception.md) | 将本测试活动 DB 可用库存置零，持续触发真实事务回滚，进入 DLQ；移除注入后超时补偿 | DLQ 中存在消息，零单，最终取消，两侧库存 200 |
| [Broker 积压](06-message-backlog.md) | SDK Consumer 正常停止后发送 20 个新预占，再同组启动并排空 | 偏移停止推进、Broker 实际积压；恢复后 21 单、库存 179、Broker 积压归零 |

## 故障位置边界

ConsumerKill 的测试方法名含 `BeforeCommit`，实际注入点是消息回调入口，**在调用 `ReservationConsumerService`、开启该业务 JDBC 事务之前**，不是在 SQL 已执行或 JDBC commit 进行到一半时杀进程。它证明“投递后、业务处理前退出且未 ACK”能被重新投递，不能用作“任意提交中断窗口都已覆盖”的证据。

Producer 的两个点也应分开解读：half message 已持久化但本地事务尚未开始；或本地事务已提交、回调尚未把 COMMIT 返回给 SDK。2026-09-13 归档使用存活同组成员，当前脚本已加强为同组无存活成员后的冷恢复（2026-09-17 回归见[台账](../validation/2026-09-13/stages-1-8-acceptance.md)）。两者都使用真实 Broker 回查，不把应用内直接调用监听器当作消息恢复。

## 指标与证据

- `flashguard_mq_backlog`：后台通过 RocketMQ Admin 采样 Broker 最大偏移与消费偏移之差。不可用为 `-1`，不是零；异步提交消费偏移会带来采样延迟。它衡量消息位置，可能包含重复投递/额外消息，不能当成唯一订单数量。
- `flashguard_reservation_backlog`：MySQL 中 PENDING/MQ_COMMITTED durable reservation 数量。它衡量尚未确认的业务记录，**不证明消息已在 Broker，也不证明 Broker 已排空**。此指标与前者不能互换。
- `flashguard_mq_dlq_messages`：消费组 DLQ 保留消息数；需要真实 Topic 偏移证据，不能用本地异常计数替代。`flashguard_mq_consume_attempts_total`、`flashguard_mq_consume_retries_total`、`flashguard_mq_consume_failures_total` 记录消费尝试、重投和失败。
- `flashguard_mq_transaction_checks_total`、`flashguard_mq_consumer_latency` 以及补偿相关指标辅助定位。测试本身直接读取 Broker Admin、MySQL 和 Redis，不要求预先启动 HTTP `/actuator` 服务。

本次[故障观测](../validation/2026-09-13/real-mq/fault-observations.txt)记录 MySQL 消费失败 3 次、失败的 Broker 重投 2 次、恢复后成功重投 1 次；Redis 确实出现晚到预占并恢复库存 200；Broker 积压实际 20 → 0。测试使用官方 RocketMQ 5.3.2 Maven 构件，隔离 Linux 容器 Java 17.0.20.1，宿主应用 Java 21.0.9。

每次查看 `D:\code\flashguard-workbench\flashguard\target\surefire-reports\` 中当前 `RocketMqIntegrationTest` 的 XML/文本报告，核对选中的测试方法、失败/错误/跳过数量和运行时间；`target\rocketmq-it\<随机目录>\` 保存 Broker/子进程日志、配置与崩溃检查点。报告目录会被后续运行更新，正式记录应复制当次证据并关联源码版本。

包装器输出 `PASS: 场景` 只表示该次所选测试与打包成功；最终结论仍以最新原始报告和断言覆盖范围为准。六个场景不是性能容量、百万 HTTP 请求、百万历史订单或 Redis Cluster 分桶验收。
