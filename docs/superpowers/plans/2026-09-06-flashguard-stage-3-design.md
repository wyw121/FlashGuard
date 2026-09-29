# FlashGuard 阶段 3 设计：RocketMQ 持久化预留 + 事务补偿起点

## 目标

把阶段 2 的 Redis PENDING 接入可靠异步链路前置条件：

- Redis 成功预占后进入数据库 `stock_reservation` 持久化；
- MQ half message 以 `reservation_id` 为事实主键发起；
- 消费方以 `reservation_id` 做幂等消费、最终确认订单状态。

## 任务 1：Durable reservation 入库

- 新增 `stock_reservation` 表与约束；
- MyBatis 实体/Mapper；
- 将 Reservation 预占信息 (`reservationId/activityId/userId/requestId/expireAt`) 落为 `PENDING`；
- 仅成功时写入，不成功时不写库。

### 验收

- 成功路径可重复执行预占重放仍返回同一 `reservationId`；
- 同一 activity 同 requestId/ userId 的异常竞争不会产生重复持久化记录；
- 约束冲突时的异常能被上层映射。

## 任务 2：参与 API 的第一阶段接入（预留）

- `/api/v1/activities/{activityId}/participations` 在第一版切换时：
  - 先走 Redis reserve；
  - `SUCCESS/REPLAY` 进入 `stock_reservation` 入库；
  - 先对外返回待确认（或与现阶段状态保持兼容）。
- `ONE_PERSON_ONE_ORDER` 的旧同步路径保留对照（先不删除，避免一次性切换）。

## 任务 3：文档与验收日志

- 更新 ADR/架构说明；
- 补充下一轮验证记录入口；
- 在验证文档说明：阶段 2 仍为内部组件，阶段 3 起始为数据库事实层承接。

