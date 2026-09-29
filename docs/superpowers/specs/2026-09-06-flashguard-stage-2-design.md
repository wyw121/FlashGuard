# FlashGuard 阶段 2：Redis Lua 预占层

依据工作台《FlashGuard_实施方案书.md》第 6 节阶段 2，以及用户继续按方案书实施的授权。本阶段是独立可验收的内部预占组件，阶段 3 再连接参加 API、durable reservation 和 RocketMQ；当前 HTTP MySQL 基线继续用于对照，不能把 PENDING 当作订单成功。

## 边界与方案

- Java 17 / Boot 3.5.16 保持现有版本，新增 Boot 管理的 spring-boot-starter-data-redis。正式镜像锁定 redis:7.4.8，Compose 的 redis 服务使用 redis profile，可单独启动。
- `reservation` 包提供 RedisReservationStore（脚本与查询）和 ReservationWarmupService（通过 ActivityQueryService 获取数据库快照）。只允许显式预热，reserve 热路径不访问 DB，不提供公开重置库存接口。
- 单活动单 slot。四个 key 共用 `fg:{activity:<id>}:` 前缀：`stock` HASH、`requests` HASH、`users` HASH、`pending` ZSET。请求 ID 作为 HASH field，无需把用户输入拼成 key。ID 保持字符串以避免 Lua double 丢失大整数精度。
- stock 包含 schemaVersion、initialStock（本次可售快照）、availableStock、startAt/endAt（UTC epoch milliseconds）。预热从 DB availableStock 出发；活动必须为 ACTIVE，开始时间可在将来。原子初始化仅在所有 key 都不存在时发生；重复预热返回 ALREADY_INITIALIZED，不回填已扣库存。存在残缺/错误类型的数据则拒绝自动重建。
- reserve 一次脚本完成请求重放、身份冲突、用户预检、库存和时间窗判断、扣减、PENDING 记录、用户占用及超时索引。核心返回 SUCCESS / SOLD_OUT / DUPLICATE / REPLAY；额外明确 NOT_READY / REQUEST_CONFLICT / NOT_STARTED / ENDED，损坏状态抛系统异常且不继续扣减。
- requestId 在活动内区分大小写、绑定 userId。同一活动同 requestId 换用户必须 REQUEST_CONFLICT；同一用户换 requestId 返回 DUPLICATE。请求重放返回原 reservationId、createdAt、expireAt、status，不续期或再次扣减。阶段 3 对接现有全局 request_id 唯一约束时必须显式解决作用域差异。
- reservation 字段：reservationId、activityId、userId、requestId、status=PENDING、createdAt、expireAt。使用 Redis TIME，默认 reservation-timeout=120s；到期只表示待扫描，不自动释放。
- 四种 key 均无自动 TTL。PENDING 和幂等记录不能因时间到就消失；未来阶段 3/5 以状态机确认、补偿和对账后再定义终态清理。当前禁止在线删除 key 或混用同一活动的 MySQL 下单与 Redis 预占库存快照。
- 脚本在写入前验证 key 类型、必要数字字段、关联状态，避免可预见的运行时错误导致写到一半（Lua 原子执行不等于回滚）。Redis 不可用时抛系统异常，不自动降级到 DB。

## 验收

真实 Redis 集成测试：1000 请求/100库存恰好100次 SUCCESS、900次 SOLD_OUT、remaining=0；100次同请求重放结果不变；同用户不同请求并发只占一次；未预热、未开始、结束、跨活动隔离、大小写、超过2^53的ID、重复预热、损坏 key、无TTL、脚本缓存丢失后恢复。查询以 activityId + requestId 返回原记录。

测试必须报告实际镜像；目标镜像不可拉取时可显式 `flashguard.test.redis.image` 选择已缓存镜像，但要标记为回退验证，不能冒称 Redis 7.4.8 已验收。阶段1原测试必须保持通过。

## 依据

- [Redis Lua 执行模型](https://redis.io/docs/latest/develop/interact/programmability/eval-intro/)
- [Spring Data Redis 3.5 脚本执行与缓存](https://docs.spring.io/spring-data/redis/reference/3.5/redis/scripting.html)
- [Redis 7.4.8 release notes](https://raw.githubusercontent.com/redis/redis/7.4.8/00-RELEASENOTES)
