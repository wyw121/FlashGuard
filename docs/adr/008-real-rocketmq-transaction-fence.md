# ADR 008：真实 RocketMQ 事务消息、持久取消栅栏与明确参与模式

- 日期：2026-09-13
- 状态：实现决策；真实 Broker 故障验收以独立验证报告为准
- 范围：阶段 3–5 的真实消息传输；既有订单事务和补偿服务继续承担业务一致性

## 背景

此前 Redis 预占后直接写入 durable reservation，只能证明预占记录存在，不能证明消息已到 Broker。数据库扫描后直接调用 Consumer 也无法证明 RocketMQ 重试、DLQ 或事务回查可用。需要真实事务生产者和 Push Consumer，并区分本地逻辑测试与真实消息验收。

## 决策

采用锁定版本的 RocketMQ 5.3.2 Java remoting 客户端：`TransactionMQProducer`、`TransactionListener` 和 `DefaultMQPushConsumer`。启用条件为 `flashguard.mq.enabled=true`。Producer 关闭 VIP 通道，使用配置的 Broker 监听端口；检查线程池、消费线程池、队列缓存和重试次数均有界。启动与关闭由 Spring Bean 生命周期管理。

`flashguard.participation.mode=mysql|async` 默认 `mysql`：

- `mysql` 明确走已有 MySQL 原子事务基线，不预占 Redis。
- `async` 要求真实 Publisher 和已预热 Redis。Redis 不可用、未预热、MQ 发送失败或结果不明确时返回 `SERVICE_UNAVAILABLE`，不隐式切到 MySQL。请求可用同一 requestId 查询或重试。
- Redis SUCCESS/REPLAY 均交给 Publisher。先发送 half message，随后由 RocketMQ 回调执行独立 Spring 事务，插入 reservation 并转为 MQ_COMMITTED。已有 durable 行也不能单独证明 Broker 收到消息，因此未完成请求重试仍发送事务消息；Consumer 负责幂等。

`MQ_COMMITTED` 在这里表示本地事务已允许提交消息，不是 Broker 最终 ACK 的跨资源强一致证明。应用在提交 DB 后失去连接时，Broker 回查读取相同事实继续提交。

## 回查与取消栅栏

回查读取数据库，匹配 reservationId、requestId、activityId、userId 和 expireAt。身份一致的 PENDING、MQ_COMMITTED、CONFIRMED 返回 COMMIT；CANCEL_PENDING、COMPENSATION_FAILED、CANCELLED 返回 ROLLBACK。身份不一致不提交、不补偿其他记录。

数据库不可用或提交结果不明确返回 UNKNOWN。缺行且尚未过期也返回 UNKNOWN，为尚未完成的本地回调保留时间。

缺行且已经过期时，先在单独数据库事务内插入 CANCEL_PENDING，再允许 ROLLBACK 并调用现有补偿服务。这个持久行是取消栅栏：晚到的本地回调读取它后不能转回 MQ_COMMITTED。并发插入或锁竞争失败时返回 UNKNOWN，由下一次回查读取胜者；不能依据一次失败判断此前没有提交。

现有 Redis orphan scanner 也使用 durable 行和补偿所有权。它与本地回调竞争时，MySQL 行锁、唯一约束和状态条件更新决定谁先取得所有权；取消状态不能复活。消费事务同样锁定 reservation，使取消和下单互斥。补偿仅在 durable CANCEL_PENDING 后释放 Redis，并保留失败状态继续扫描。

不在模糊的 send/commit 异常后直接释放 Redis，因为 Broker 可能已经收到了 half message 或 DB 已经提交。此时保留 Redis 预占给回查和超时扫描恢复。进程在预占后、half message 前退出，也由 orphan scanner 恢复。

## 跨活动 requestId 并发冲突恢复

本地 MQ 提交、缺行取消与冲突取消申请使用 `READ_COMMITTED`。真实 20 并发首请求在默认 `REPEATABLE_READ` 下触发了缺行 `FOR UPDATE` 的 gap lock/插入死锁；已有行在 RC 下仍然加行锁，缺行并发由 requestId/reservationId 唯一约束和取消栅栏主键裁决。事务代理返回后才能向 Broker 报告决策或释放 Redis。

事务监听器只对代理已回滚并抛出的 `PessimisticLockingFailureException` 做最多三次本地事务尝试，间隔 25/50 ms。每次重新跨越事务代理，不重新发送 half message，也不在已失败的事务内部重试。普通模糊异常不自动重试，重试耗尽仍返回 UNKNOWN，保留预占给回查和扫描器处理。

此保证要求 durable 请求身份不被删除后重用或转移给其他预占。当前系统没有此类归档/删除功能；将来增加归档必须保留请求所有权和取消栅栏，重新验证迟到回调。不能把 RC 的缺行查询描述成“锁住不存在的行”。

requestId 的服务层预检可拒绝已经存在的异身份请求，但两个活动的并发首请求仍可能都预占 Redis，随后只有一个 durable reservation 能通过全局 requestId 唯一约束。不能修改胜者记录来补偿败者。

独立 `reservation_cancellation_fence` 表按 reservationId 保存败者完整身份和 CANCEL_PENDING/CANCELLED 状态，不对 requestId 添加唯一约束。过期扫描发现冲突后，通过独立 Spring 事务锁定取消栅栏、败者 reservation 槽位和已存在的胜者记录；确认败者没有 durable reservation 或已完成订单后才插入栅栏。MQ 本地回调先锁定并读取同一个取消栅栏，存在时直接返回取消状态，不能再插入或提交败者任务。发现另一 requestId 胜者时也会锁定胜者后拒绝本地提交。

取消栅栏事务提交后才执行 Redis `releaseExpected`，Lua 在修改前核对 reservationId 和 userId，幂等标记也必须匹配预期 reservationId。胜者所在活动和 durable 行不受影响。独立的数据库栅栏扫描重试 CANCEL_PENDING，因此即使释放 Redis 后进程退出、Redis pending zset 已删除，仍能恢复并标记 CANCELLED。失败重试保留栅栏，不自动释放身份不匹配数据。

真实 MySQL/Redis 回归需要证明同 requestId 两活动预占后败者只释放一次、胜者订单和库存保持不变、释放后 DB 更新前崩溃可恢复，以及晚到回调与栅栏竞争无法提交败者。测试结果以验证报告为准。

## 消费与重试

生产者重启后即使没有新请求，也必须能够被 Broker 找到并参与事务回查。`RecoverableTransactionProducer.start()` 显式加载业务 topic 到客户端路由缓存，再发送生产者心跳；客户端自身的周期心跳继续维护注册。仅查询 topic 的管理接口不会填充该客户端缓存。生产部署必须先创建 topic，再启动应用；真实强杀测试覆盖恢复实例没有新发布请求的情况。

真实 Push Consumer 每批 1 条，校验消息和 durable 身份后调用既有 `ReservationConsumerService` 事务。订单唯一约束、库存更新和 CONFIRMED 状态更新保持同一 DB 事务。已取消消息直接 ACK；若取消在初次读取与订单事务锁之间发生，适配器再次确认取消状态后 ACK。

暂态异常返回 RECONSUME_LATER，交给 Broker 重投。`max-reconsume-times` 默认 3，即首次消费之外最多 3 次重试；持续失败由 RocketMQ 转到消费组 DLQ。默认遵守 Broker 延时策略；`retry-delay-level` 可用于隔离验证缩短重试等待。适配器不把应用内循环当作真实重试，也不把最终失败计数当作已观测到 DLQ。

新增指标记录事务回查决策、发布成功/模糊失败、消费尝试、重投次数和失败次数。工作线程使用消息关联信息并在结束时清理；事务回调恢复调用前 MDC，避免破坏同步 HTTP 请求上下文。

## 边界与验证要求

单元测试覆盖状态判定、DB 不可用、缺行取消栅栏、晚到回调、取消消息 ACK、暂态消费错误及 async 禁止回退。这些不能替代真实 Broker 验收。需要另行保留真实发送/回查、100 次重复投递、暂态恢复、持续失败进入 DLQ、进程强制退出前后恢复等证据。本文不声称这些故障实验已经通过。

Broker 的事务检查次数和消息保留期仍是运维边界，必须长于业务恢复窗口；超时扫描负责释放库存，但不能从扫描成功推断 MQ 交付成功。手工重放 DLQ 应重新校验 durable 状态，已取消任务不会重新下单。

## 官方依据

- [RocketMQ 事务消息发送与 TransactionListener 回调](https://rocketmq.apache.org/zh/docs/4.x/producer/06message5/)
- [RocketMQ 事务消息语义及事务超时边界](https://rocketmq.apache.org/docs/featureBehavior/04transactionmessage/)
- [RocketMQ 5.3.2 DefaultMQPushConsumer 源码](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/client/src/main/java/org/apache/rocketmq/client/consumer/DefaultMQPushConsumer.java)
