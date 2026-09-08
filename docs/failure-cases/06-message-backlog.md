## 脚本

- `scripts/failure-cases/inject-message-backlog.ps1`

## 故障现象
- 由于消费速度不足，`PENDING/MQ_COMMITTED` 累积。

## 预期
- 指标显示 backlog 上升。
- 恢复消费后 backlog 回落。

## 实际
- 记录: （运行脚本后填）

## 恢复
- 触发消费并观察 `flashguard_mq_backlog` 回到稳定基线。

## 是否丢单 / 超卖
- 堆积期间不应超卖。
- 恢复消费后不应丢单。
