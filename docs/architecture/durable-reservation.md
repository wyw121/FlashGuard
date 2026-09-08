# Durable Reservation（阶段 3）

本阶段目标：把阶段 2 的 Redis 预占从“内存快照建议”升级为“有可恢复事实来源”。

## 1. 事实源边界

Redis `reserve` 只负责高并发入口判断与预占。它只返回：

- `SUCCESS`：本次请求完成预占；
- `REPLAY`：同一请求重复；
- 其他拒绝码或异常。

一旦返回 `SUCCESS`，在本地事务里必须落库一条 `stock_reservation`，状态为 `PENDING`。

## 2. 数据模型

`stock_reservation`：

- `activity_id + user_id` 保证同活动一人一单语义有持久化支撑；
- `request_id` 与订单链路可共享幂等语义；
- `status` 提供 RocketMQ 事务回查和补偿扫描状态；
- `message_key` 预留给消息层幂等。

## 3. 暂定状态机

```text
PENDING -> MQ_COMMITTED -> CONFIRMED
PENDING -> CANCEL_PENDING -> CANCELLED
PENDING/CANCEL_PENDING -> COMPENSATION_FAILED
```

最终实现中每一步都要是幂等的（重复提交不应产生副作用）。

## 4. 当前边界

- 阶段 3 首轮先不改参与 HTTP 请求返回语义，仅先补齐持久化模型和接口；
- 同步下单基线链路保留用于对照，避免“把 PENDING 当作订单成功”；
- 阶段 5 的扫描和对账会基于 `stock_reservation` 与订单/Redis 的状态一致性。

