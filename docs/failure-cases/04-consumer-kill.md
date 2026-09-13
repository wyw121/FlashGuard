# 04：Consumer 投递后、业务事务前退出并重投

状态：**PASS（2026-09-13 对应真实 MQ 方法）**。见[保留结果](../validation/2026-09-13/real-mq/results.json)和[JUnit 报告](../validation/2026-09-13/real-mq/junit-result.txt)；不代表全项目或任意事务中断位置均通过。

前提：Docker Desktop Linux engine、Java 17+、Maven、PowerShell 7，以及 `D:\code\flashguard-workbench\work-notes\maven-settings.xml`。组件、活动、Topic 和消费组由测试创建，无需预先启动应用或创建活动 1001。

```powershell
pwsh -NoProfile -File 'D:\code\flashguard-workbench\flashguard\scripts\failure-cases\inject-consumer-kill.ps1'
```

映射到 `RocketMqIntegrationTest#consumerProcessHaltBeforeCommitRedelivers`。专属 Consumer 子 JVM 先写 READY 标记，真实 Producer 再发送事务消息。子进程收到 Broker 消息后，在回调入口写入检查点并执行 `Runtime.halt(137)`，尚未 ACK。

**虽然测试方法名包含 BeforeCommit，实际杀进程位置在调用 `ReservationConsumerService`、开启业务 JDBC 事务之前；没有执行订单 SQL，更没有在 JDBC commit 中途杀进程。** 此结果验证“收到消息但尚未开始业务处理就退出”的重投恢复，不能宣称事务执行到一半的进程中断已被覆盖。

退出后检查订单为零、reservation 仍为 MQ_COMMITTED，并确认退出码 137 与检查点内容。随后启动同消费组的新生产适配器 Consumer，等待真实 Broker 重新交付。最终要求 reservation=CONFIRMED，同 reservation 恰好一条订单，Redis 和 DB 可用库存均从 200 变为 199。

保留 `D:\code\flashguard-workbench\flashguard\target\rocketmq-it\<随机目录>\` 中的 READY/崩溃检查点、worker 日志与 Broker 日志，以及 `D:\code\flashguard-workbench\flashguard\target\surefire-reports\` 当前方法报告。客户端正常停止后再排空积压是[另一个场景](06-message-backlog.md)，不把那种正常 shutdown 当作本例的进程强制退出。
