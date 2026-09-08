## 脚本

- `scripts/failure-cases/inject-consumer-forced-exception.ps1`

## 故障现象
- 消费阶段人工抛错（通过 `/api/v1/admin/faults/consumer/forced-exception` 开关）。

## 预期
- 消费失败时，投递链路保持可重试，不影响库存主链路的一致性边界。
- 关闭故障开关后重试可成功，且无重复扣库存和重复建单。

## 实际
- 记录: （运行脚本后填）

## 恢复
- 关闭故障开关并重试消费。

## 是否丢单 / 超卖
- 不应丢单。
- 不应超卖。
