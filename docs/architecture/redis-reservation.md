# Redis 预占组件（阶段 2）

本阶段提供内部服务，HTTP 仍为阶段 1 的 MySQL 基线。预占只创建 `PENDING`，不会创建订单。阶段 3 将消费这个边界并接入 durable reservation、事务消息和失败补偿。

## 数据和所有权

同一活动使用前缀 `fg:{activity:<activityId>}:`。花括号中的 hash tag 确保脚本的所有 key 属于同一 Cluster slot；本阶段运行单节点 Redis，不宣称已验证 Cluster 部署。

| 后缀 | 类型 | 内容 | 自动 TTL |
| --- | --- | --- | --- |
| stock | HASH | schemaVersion、initialized、本次预热可售库存、剩余库存、活动时间窗 | 无 |
| requests | HASH | requestId → 完整预占记录 | 无 |
| users | HASH | userId → 已占用的 requestId | 无 |
| pending | ZSET | requestId，score 为 expireAt 毫秒时间戳 | 无 |

requestId 在单个活动内区分大小写并绑定 userId。相同请求和相同用户返回原记录；相同请求换用户返回 REQUEST_CONFLICT；同一用户换请求返回 DUPLICATE。请求 ID 不作为 key 的组成部分，因此特殊字符不会改变 hash tag。

预热只创建 stock 元数据；另外三个集合在第一次预占时创建，空集合在 Redis 中表现为 key 不存在（TTL=-2）。已存在的 key 均不设 TTL（TTL=-1）。元数据不占用 requests/users 的业务字段或 pending 的业务成员，`__schemaVersion` 等请求值也不会与内部标记冲突。

每次操作以 `initialStock - availableStock` 校验 requests/users/pending 的条目数，相关请求还验证用户映射和超时索引。检查是 O(1)，不会遍历整个活动；它能拒绝已检测到的残缺状态，不代替阶段 5 的全量对账。

reservation 保留 reservationId、activityId、userId、requestId、status、createdAt、expireAt。activityId/userId 传入 Lua 时是十进制字符串，避免超过 2^53 后失去精度。时间统一为 UTC epoch milliseconds，由 Redis TIME 产生创建时刻。

## 预热与预占

`ReservationWarmupService` 按 activityId 调用数据库查询服务，向 `RedisReservationStore` 传入快照。预热要求 ACTIVE 活动，允许开始时间在将来；使用 DB availableStock 作为可售快照。原子脚本只初始化完全不存在的活动数据。重复预热返回 ALREADY_INITIALIZED，不补回已扣库存，也不自动修改旧时间窗。

reserve 不查数据库。在一次 Lua 操作内完成：检查 key 类型和已有记录 → 请求重放/冲突 → 用户重复预检 → 时间窗和库存判断 → 扣减并记录 PENDING、用户占用与超时索引。错误类型或损坏状态在写入前拒绝，Redis 故障也不会自动切换为直接写 DB。

同一活动结束后的重放仍可读取原记录，预占成功后过了 expireAt 也不能再次扣减。expireAt 仅用于后续扫描，不是终态、自动释放命令或删除时间。

## 状态保留与恢复边界

默认预占超时 120 秒。四类 key 存在时 TTL 均为 -1；应用不会通过 TTL 隐式遗忘未处理库存。当前没有扫描器，不会在 120 秒后自动回补。未来要先按 MySQL durable 状态决定确认/补偿，再定义终态记录的保留和清理策略。

Compose 使用 AOF 和 noeviction。它们不能保证 Redis 崩溃后预占绝不丢失；不能把 AOF 当作 MySQL durable reservation 的替代品。部分 key 缺失时也不能用 DB 库存直接重建在线活动，应停止准入并在阶段 5 通过审计和对账处理。

当前基线订单表的 request_id 是全局唯一，而 Redis 是活动内幂等；阶段 3 整合接口时必须明确解决这一作用域差异。也不能把本阶段的 SUCCESS 直接映射成订单 CONFIRMED。

## 运行与验证

从项目根目录执行：

```powershell
docker compose --profile redis up -d --wait redis
```

这是启动依赖，不会自动预热所有数据库活动。内部调用方应显式调用 warmup 服务一次，再调用 store 的 reserve 和 findByRequestId；没有开放 HTTP 库存重置接口。

在 Spring 管理的调用方中注入这两个服务后，可使用以下接口（不是公开 HTTP API）：

```java
warmupService.warmUp(activityId); // 需要数据库中存在 ACTIVE 活动
ReservationResult result = store.reserve(activityId, userId, requestId);
Optional<PendingReservation> saved = store.findByRequestId(activityId, requestId);
```

组件位于 `com.flashguard.reservation`。返回 `ReservationResult.decision()` 描述准入结果；仅 SUCCESS / REPLAY 携带原 `PendingReservation`。调用方仍需在阶段 3 完成消息与数据库事务。

```powershell
mvn '-Dtest=RedisReservationStoreTest,ReservationWarmupServiceTest' test
```

真实 Redis 测试的默认镜像也是 `redis:7.4.8`。受网络影响时只允许显式覆盖测试镜像，并在报告中写出真实版本；默认验收目标不随本机缓存改变。

验证同时记录 1000 个请求的提交方式与并发度，不把请求总数当作同时在途请求数，不将正确性测试写成 QPS 结果。完整命令和本轮观测见 [验证记录](../validation/2026-09-06-stage-2.md)。
