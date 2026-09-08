# ADR 005: 阶段4 幂等消费确认订单

- 状态：Draft
- 日期：2026-09-07

## 决策

在阶段 4，将 `stock_reservation` 作为消息消费幂等事实来源，新增消费侧确认服务：

- 通过 `reservation_id` 读取 `stock_reservation`（`FOR UPDATE`）；
- 仅允许 `PENDING` 进入消费，先 CAS 到 `MQ_COMMITTED`（`markAsMqCommitted`）；
- 在同一数据库事务内按 `activity_id` 原子扣减库存、写入订单、更新 `stock_reservation.status=CONFIRMED`；
- 查询成功订单可直接回放，重复消息不重复扣减；
- 订单表新增 `reservation_id`，并创建唯一索引用于对账与幂等追踪。

## 风险与边界

- 当前服务暂未接入 MQ；`consumeReservation(reservationId, messageKey)` 提供消费核心逻辑闭环；
- 仅 `PENDING`/`MQ_COMMITTED` 参与确认链路，非该状态将返回系统级异常，进入重试窗口由上层处理；
- 与 MQ 回查、超时补偿仍在阶段 5/阶段5后续补上，当前不提供“卡住的 MQ_COMMITTED 自动回收”逻辑。

## 通过标准

- 新建 `V3__add_reservation_id_to_orders.sql`；
- 新建 `OrderMapper.insertWithReservation`、`OrderMapper.findByReservationId`、`StockReservationService` 状态更新能力；
- 实现 `ReservationConsumerService.consumeReservation` 为消费幂等核心；
- 文档更新：阶段3验收手册继续使用业务拒绝与系统错误分离的指标口径。

