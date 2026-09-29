# FlashGuard：从请求到订单的讲述提纲

## 先讲真实请求

客户端调用 `POST /api/v1/activities/{activityId}/participations`，提交 `userId` 和 `requestId`。活动详情由 Caffeine + Redis 缓存读取；活动级 QPS 和用户短窗口限频保护入口。`ParticipationService` 按显式配置选择同步 MySQL 或异步模式，不在 Redis/MQ 故障时偷偷切回同步模式。

同步基线在一个 MySQL 事务里扣库存并写订单。`ActivityStockMapper.decrementIfAvailable` 的核心 SQL 是：

```sql
UPDATE activity_stock
SET available_stock = available_stock - 1,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE activity_id = ? AND available_stock > 0;
```

更新零行表示无库存。`orders` 上的 requestId、reservationId 和活动/用户唯一约束提供最终幂等防线，不能只靠客户端或 Redis 的重复检查。

异步入口先用 Redis Lua 在同一个活动 hash tag 内原子完成身份/幂等/库存检查、扣减与 PENDING 记录。Redis 成功只能说明预占成功。生产者随后发送 half message，`ReservationLocalTransactionService` 在独立 Spring 事务代理中写 durable reservation 并标记 MQ_COMMITTED；代理返回后监听器才告诉 Broker COMMIT。客户端收到 PENDING 后查询参加结果，只有实际订单确认才是完成。

消费者进入 `ReservationConsumerService.consumeReservation`，锁定 reservation；已经 CONFIRMED 就读取原订单，否则在一个 MySQL 事务里扣 DB 库存、插订单、确认 reservation。网络重投、ACK 丢失和相同用户竞争都要由这条链路处理，不能假设消息恰好投递一次。

## 按故障窗口解释恢复

| 窗口 | 恢复依据 |
| --- | --- |
| Redis 成功，half message 尚未发送就退出 | RedisOrphanRecoveryJob 扫描过期索引、补建可审计 durable 状态并执行幂等补偿 |
| half message 已到 Broker，本地事务未开始就退出 | 回查等待合理窗口；过期仍无记录则持久化取消权，再释放 Redis，防止迟到本地事务重新提交 |
| DB 本地事务已提交，COMMIT 响应未返回就退出 | Broker 回查数据库事实；MQ_COMMITTED/CONFIRMED 等可提交状态返回 COMMIT |
| 数据库暂时不可达、提交结果不明 | 返回 UNKNOWN，等待回查；不能因为捕获异常就认定数据库回滚并立即归还库存 |
| Consumer 失败或未 ACK | Broker 重试，消费事务与唯一约束保证幂等；持续失败进入 DLQ，超时补偿负责后续收敛 |
| 补偿期间 Redis 不可用 | 保留 CANCEL_PENDING / COMPENSATION_FAILED 并重试；释放 Lua 检查状态，归还只能发生一次 |

订单是最终业务事实。对账检查 `initial = DB available + orders`，以及 `DB available - open reservations = Redis available`，将无法证明安全的差异写入 `reconciliation_issue`。不直接执行“Redis 库存覆盖成 DB 库存”，否则可能把尚未消费的预占库存重新卖出。

默认 reservation 超时为 120 秒，durable 扫描间隔为 30 秒，孤儿扫描默认间隔为 5 秒。这些是配置，不是实测收敛 SLA：批次、活动数量、Broker 回查和依赖故障都会影响耗时，故障恢复时间须引用实际观测。

## 性能数字必须跟着实验条件讲

正式 HTTP 协议使用 constant-arrival-rate，预热 60 秒排除统计，稳态 180 秒重复五轮。它能暴露请求堆积和 dropped iterations；不能把服务变慢后负载生成器自动减速误当作稳定吞吐。

全成功、售罄、重复 requestId、混合用户竞争、限流分别统计。重复请求得到原订单不算新增订单；混合流量中业务拒绝不算系统错误，也不能充当订单吞吐。PENDING 必须继续轮询到 CONFIRMED。系统错误、丢弃迭代或库存/订单/MQ 不变量失败，均停止后续轮次并保留原始数据。

2026-09-17 原有八场景的 10 requests/s 五轮结果已归档，同步/异步全成功的实际订单 RPS 中位数约为 9.915 / 9.801。只能说在该配置下通过，不能说系统最大 QPS 为这些值。性能实验关闭了定时补偿和对账，后台任务恢复能力由独立故障套件证明；也不能据此宣称生产全部任务同时运行时的容量。

百万历史订单实验属于 SQL 查询实验：有固定造数、索引前后 EXPLAIN ANALYZE、同结果窗口与全部计时数据。它不等于百万 HTTP 请求。Keyset 的单页收益以已持有游标为前提，首次寻找深页游标的成本另行记录。

## 解释当前没有采用的技术

生产保留单活动单槽 Lua。相同 Redis 节点上把一个 key 拆成多个 key 并不自动获得跨核写并行；Cluster 分桶需要不同 hash tag 分散到多个主节点，还会引入库存不均和跨桶协调成本。当前三主节点五轮 A/B 未建立稳定吞吐收益，因此保留实验，不启用生产分桶。

限流用于限制入口负载，熔断用于隔离失败的远程调用。当前没有需要额外熔断器治理的同步业务 RPC 链，不为技术列表增加熔断器或微服务。Redis、DB、MQ 的故障由明确超时、拒绝、重试和补偿策略处理。

对应证据：[阶段台账](../validation/2026-09-13/stages-1-8-acceptance.md)、[HTTP 协议](../../benchmark/README.md)、[SQL 实验](../validation/2026-09-13/million-orders-sql.md)、[分桶实验](../validation/2026-09-13/stage7-cluster-ab.md)、[故障记录](../failure-cases/README.md)、[ADR 索引](../adr/README.md)。
