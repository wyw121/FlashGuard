## 脚本

- `scripts/failure-cases/inject-consumer-kill.ps1`

## 故障现象
- 消费逻辑终止（当前单体尚未拆分 Producer/Consumer；进程重启会暂时中断消费）。

## 预期
- 该故障窗口内消费暂停，`PENDING/MQ_COMMITTED` 进入堆积。
- 重启后应继续消化 backlog，不丢单。

## 实际
- 记录: （运行脚本后填）

## 恢复
- 重启应用并恢复消费。

## 是否丢单 / 超卖
- 不应丢单。
- 不应超卖。
