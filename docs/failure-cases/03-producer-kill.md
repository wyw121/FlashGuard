# 03：Producer 在两个明确事务边界退出

历史状态：**PASS（2026-09-13 对应真实 MQ 方法）**，当时恢复由存活同组成员完成。见[保留结果](../validation/2026-09-13/real-mq/results.json)和[JUnit 报告](../validation/2026-09-13/real-mq/junit-result.txt)。当前代码已加强为同组无存活成员后的冷恢复，2026-09-17 全量回归见[阶段台账](../validation/2026-09-13/stages-1-8-acceptance.md)。下表描述当前脚本，不将旧报告冒充新增断言的证据。

前提：Docker Desktop Linux engine、Java 17+、Maven、PowerShell 7；Maven settings 为可选，工作台已有配置会自动沿用。测试自建组件、随机 Topic/组和活动，不需要现有应用或活动 1001。

```powershell
pwsh -NoProfile -File 'D:\code\flashguard-workbench\flashguard\scripts\failure-cases\inject-producer-kill.ps1'
```

该包装器执行两个方法：`producerProcessHaltBeforeLocalTransactionRollsBack` 与 `producerProcessHaltAfterLocalCommitRecovers`。注入使用专属子 JVM 的 `Runtime.halt(137)`，跳过正常退出钩子；测试核对退出码及包含模式/reservationId 的检查点，避免把普通启动失败当作成功故障注入。

| 注入位置 | 恢复方式 | 必须满足的不变量 |
| --- | --- | --- |
| Broker 已持久化 half message、进入本地事务回调，但尚未开启本地 JDBC 事务 | 唯一 Producer 退出后启动同组新客户端接受真实 Broker 回查；缺行到期后持久化取消状态并补偿 Redis | 回查次数 > 0；reservation=CANCELLED；零订单；Redis 库存恢复 200 |
| 本地 Spring/JDBC 事务已经提交，尚未把 COMMIT 从包装回调返回给 SDK | 唯一 Producer 退出后启动同组新客户端，按 durable 记录接受回查并 COMMIT，真实 Consumer 接收任务 | 回查次数 > 0；reservation=CONFIRMED；同预占一单；DB/Redis 库存均为 199 |

这里覆盖的是**事务开始前**和**本地提交完成后**两个位置，不是在 JDBC commit 进行中随机杀进程。测试先断言同组没有存活 Producer、订单为零、half message 尚未投递，再启动新的同组客户端；新客户端不发送应用消息，仅响应 Broker 回查。

运行时证据位于 `D:\code\flashguard-workbench\flashguard\target\rocketmq-it\<随机目录>\`：检查点、子进程日志、Broker 日志/配置；JUnit 当前报告位于 `D:\code\flashguard-workbench\flashguard\target\surefire-reports\`。报告中的两个方法均已通过；后续重跑应保留新检查点和报告，不只记录包装器最后一行。
