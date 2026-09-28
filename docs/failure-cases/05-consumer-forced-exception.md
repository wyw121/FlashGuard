# 05：持续消费事务失败、真实 DLQ 与超时补偿

状态：**PASS（2026-09-13 对应真实 MQ 方法）**。见[保留结果](../validation/2026-09-13/real-mq/results.json)和[JUnit 报告](../validation/2026-09-13/real-mq/junit-result.txt)；不代表全项目或性能验收。

前提：Docker Desktop Linux engine、Java 17+、Maven、PowerShell 7；Maven settings 为可选，工作台已有配置会自动沿用。只操作测试自行创建的活动和专属组件，不需要已运行应用或活动 1001。

```powershell
pwsh -NoProfile -File 'D:\code\flashguard-workbench\flashguard\scripts\failure-cases\inject-consumer-forced-exception.ps1'
```

映射到 `RocketMqIntegrationTest#persistentConsumerFailureReachesRealDlqThenTimeoutCompensates`。包装器沿用 `forced-exception` 文件名，但当前注入是：预占 Redis 后，将**本测试活动**的 MySQL `available_stock` 从 200 改成 0，使真实 Consumer 的扣库存条件失败；不调用管理端强制异常开关，也不把手工直接调用消费者当作 Broker 重投。

Consumer 配置 2 次重试上限。每次订单业务事务因库存约束失败而回滚，Broker 持续重投后把消息送到 `%DLQ%<消费组>`。测试通过 RocketMQ Admin 查询真实 DLQ Topic，要求最大偏移大于零，同时检查零订单、reservation 仍为 MQ_COMMITTED；单看应用异常计数不足以通过这一步。

随后只移除注入的 DB 库存故障，将可用库存恢复到 200。等该预占过期后调用真实超时补偿扫描，要求 reservation=CANCELLED、零订单、Redis 与 DB 可用库存均为 200。最终结果是可审计取消，不是这笔请求被确认成功；该测试不自动重放或删除 DLQ，也不声称 DLQ 被排空。

证据保留在 `D:\code\flashguard-workbench\flashguard\target\surefire-reports\` 和 `D:\code\flashguard-workbench\flashguard\target\rocketmq-it\`。区分 `flashguard_mq_dlq_messages` 的 Broker 保留消息数与 `flashguard_mq_consume_failures_total` 的应用失败次数；后者不能代替 DLQ 落盘证据。
