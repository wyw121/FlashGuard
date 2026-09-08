# ADR 004: 阶段 3 持久化 Reservation 状态机

- 状态：Draft
- 日期：2026-09-06

## 背景

阶段 2 已证明 Redis Lua 预占能保证请求层面的原子性。下一步需要把“高并发入口成功”变成可恢复的业务事实，避免 Redis 丢失导致 PENDING 无法追踪。

## 决策

在参与 API 与 MQ 之间增加 `stock_reservation` 持久化表作为 durable reservation：

- `reservation_id` 唯一；
- `activity_id + user_id` 唯一；
- `request_id` 唯一（与现有订单幂等约束保持一致）；
- `status` 明确为 `PENDING` / `MQ_COMMITTED` / `CONFIRMED` / `CANCEL_PENDING` / `CANCELLED` / `COMPENSATION_FAILED`；
- `message_key` 预留用于事务消息幂等。

阶段 3 的本地事务必须将 Redis 预占成功事件转为数据库中的一条 `PENDING` reservation（失败不能静默吞掉），从而给 RocketMQ 事务回查与补偿提供事实边界。

## 备选方案

1. 不写 durable reservation，直接靠 Redis 重放和幂等：  
   低成本，但无法在 Redis 丢失或异步重试场景下审计和收敛。
2. 直接在现有订单表新增状态：  
   会把请求和订单生命周期混在一起，不利于阶段 3 事务与阶段 5 对账分离。
3. 引入 Saga 表而非 reservation：  
   需要更宽泛的流程状态定义，本阶段先不扩展。

## 风险与边界

- reservation 状态机必须幂等收敛；
- `message_key` 字段可能为空，不能用于唯一幂等前提；
- `request_id` 的作用域冲突问题要在参与 API 适配时明确（阶段 2 为活动内作用域）。

## 通过标准

- MySQL Flyway 新增 `stock_reservation`；
- MyBatis 映射与服务层持久化插入/更新接口可复用；
- 保留阶段 1/2 的功能不变（HTTP 路径未直接改写，后续阶段按计划切换）。

