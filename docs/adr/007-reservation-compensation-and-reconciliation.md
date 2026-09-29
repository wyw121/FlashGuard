# 0007：可重试补偿、孤儿预占恢复与对账审计

## 状态与并发边界

MySQL 是状态事实来源。消费者在数据库事务内锁定 reservation 行；补偿以条件 UPDATE 抢占 `CANCEL_PENDING`，仅允许 `PENDING/MQ_COMMITTED/CANCEL_PENDING/COMPENSATION_FAILED` 且没有关联订单。消费者已经提交 `CONFIRMED` 时补偿不能取得取消权；补偿先取得取消权时消费者不能创建订单。

扫描覆盖四种非终态取消候选，按更新时间分页，避免永久失败的早期记录一直占满首批。Redis 释放和 MySQL 最终状态不是一个事务：先持久化取消权，再执行释放 Lua，最后写 `CANCELLED`。任何中断都可以从 `CANCEL_PENDING` 或 `COMPENSATION_FAILED` 重试。失败标记写入失败不终止整个批次。保留原 message_key，不以扫描任务的标记覆盖原消息关联。

## Lua 幂等

新增 `fg:{activity:<id>}:released` hash，与原四个 key 同 slot。Lua 在任何写入前校验状态、身份、类型、库存上界和关联基数；成功释放后写 requestId → reservationId 墓碑，并移除当前 request/user/pending 映射。重复释放读到墓碑直接成功，不再加库存。已释放 requestId 不能重新预占；同一用户可以使用新 requestId 重试。

所有这些 key 都不设置自动 TTL。墓碑必须与该活动的全部预占元数据一起归档；不能单独删除，否则迟到重试可能丧失幂等依据。当前没有自动归档实现。

V5 将 durable 表的“一用户一条历史记录”改为“一用户最多一条有效记录”：生成列 `active_user_id` 在 `CANCELLED` 时为 NULL，其余状态为 user_id，唯一索引为 `(activity_id, active_user_id)`。取消记录继续保留，订单的 `(activity_id,user_id)` 和 reservation_id 唯一约束保持不变。

## Redis 成功、DB 未写入的崩溃窗口

独立任务每 5 秒扫描一页过期 Redis requestId。先查询/补建 durable 记录，核对 reservation/activity/user 身份后调用同一取消流程。与生产者同时插入时用唯一约束和重新查询消除竞争；身份不符写对账问题，不盲目释放。

读取原始 requestId 页与解码记录分离，单条损坏记录写审计且不阻塞同页健康记录。当前确认订单对应的 Redis 条目仍保留在 pending zset，因此游标必须能越过这些条目。每页最多 200 条，扫描完活动后遍历下一活动，末尾回到起点。删除造成的页位移会在下一轮覆盖；调度关闭时不会自动收敛。恢复时间由扫描周期、活动数、页数和存储可用性共同决定，不能承诺所有故障固定 120 秒内恢复。

## 对账不自动覆盖库存

数据库计数在 REPEATABLE_READ 事务中读取，绕过活动详情缓存。检查：

- `initialStock - dbAvailable - orders = 0`；
- `dbAvailable - dbOpenReservations - redisAvailable = 0`；
- Redis 保留条目数 = DB 未完成预占数 + DB 已确认预占数；
- `CONFIRMED` 预占必须有对应订单；
- Redis 元数据缺失、非数字库存、错误 key 类型、连接失败也形成审计问题。

跨 Redis/MySQL 不能得到原子快照，并发更新可能造成短暂差异；这些是待复核候选，不能直接解释为已证实丢单。`reconciliation_issue` 保存问题明细、首次/最近观察时间和观察次数，同一活动同一差异聚合；不会自动覆盖库存或自动关闭问题。定时任务每 60 秒最多处理 100 个活动。

## 尚未完成的验收

真实 RocketMQ 事务消息、回查、retry/DLQ 和进程 kill 故障链路仍待实现/执行。本轮真实 MySQL/Redis 测试覆盖本地状态机、消费竞争与中断点重建，不等同于 Broker 验收或生产容量结论。
