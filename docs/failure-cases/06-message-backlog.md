# 06：正常停止 Consumer 后积压，再同组恢复排空

状态：**PASS（2026-09-13 对应真实 MQ 方法）**。见[结果摘要](../validation/2026-09-13/real-mq/results.json)与[Broker 偏移观测](../validation/2026-09-13/real-mq/fault-observations.txt)：本次实际积压为 **20 → 0**。不代表全项目或容量验收。

前提：Docker Desktop Linux engine、Java 17+、Maven、PowerShell 7，以及 `D:\code\flashguard-workbench\work-notes\maven-settings.xml`。包装器自行创建专属组件和测试活动，无需现有应用或活动 1001。

```powershell
pwsh -NoProfile -File 'D:\code\flashguard-workbench\flashguard\scripts\failure-cases\inject-message-backlog.ps1'
```

映射到 `RocketMqIntegrationTest#stoppedConsumerRestartsAndDrainsBrokerBacklog`。先启动 Consumer 并完成一单，等消费偏移提交、积压归零。然后对该 SDK Consumer **正常调用 shutdown**，记录停止时 Broker 与消费者偏移；此例不是杀掉处理中的进程。

Consumer 停止期间通过真实事务 Producer 发送 20 个不同预占。测试要求订单仍只有 1 条、MySQL 中该活动 MQ_COMMITTED 预占恰好 20 条，同时由 RocketMQ Admin 验证：Broker 最大偏移至少增加 20，消费者偏移保持不变，真实积压等于最大偏移与消费偏移之差。

本次保留观测为 `brokerOffsetBefore=4`、`consumerOffsetBefore=4`、`uniqueReservations=20`、`pendingMessages=20`。不要假设任何运行的绝对初始偏移都从 0 开始；业务预占数与 Broker 消息位置分别检查。

重新启动同消费组的生产 Consumer 后，等待最终 21 条订单、这 20 个新增 reservation 全部 CONFIRMED，并继续等 Broker 消费偏移提交至积压 0。最终 MySQL 与 Redis 可用库存均为 179，证明 21 个独立订单各扣一单位。

`flashguard_mq_backlog` 采样 Broker 偏移差，不可用为 -1，归零可能滞后业务提交；`flashguard_reservation_backlog` 只统计 DB 中 PENDING/MQ_COMMITTED 的业务预占。DB 预占归零不能替代 Broker 积压归零的断言，消息重复也可能使两者数量不同。

查看 `D:\code\flashguard-workbench\flashguard\target\surefire-reports\` 当前报告和 `D:\code\flashguard-workbench\flashguard\target\rocketmq-it\` Broker/客户端日志，保留 `FLASHGUARD_BROKER_BACKLOG` 前后两行。该小批量恢复测试不是积压容量或高负载恢复吞吐结论。
