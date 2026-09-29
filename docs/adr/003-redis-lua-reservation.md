# ADR 003：用 Redis Lua 原子预占，保留可追踪的 PENDING

- 状态：Accepted（阶段 2 内部组件；HTTP/MQ 链路尚待阶段 3）
- 日期：2026-09-06

## 背景与约束

阶段 1 已有 MySQL 条件扣减和订单唯一约束。阶段 2 要验证高并发入口的预占正确性，同时为阶段 3 的可靠异步链路保留状态。本阶段不将 Redis 记录视为最终订单，不宣称 Redis、MySQL 与 MQ 的跨资源强一致。

## 候选方案

1. 每个请求都访问 MySQL 热点库存行：继续作为可比较的基线。
2. Redis 分布式锁：需要管理锁持有时间和释放，检查与更新分散在多个网络操作中。
3. 短 Lua 脚本：一次执行请求幂等、用户预检、库存判断、扣减和 PENDING 写入。

## 选择

采用方案 3，以四个共用活动 hash tag 的 key 表达一个原子边界。Java 层验证输入并装载脚本；Lua 使用 Redis TIME 判断时间窗。ID 作为字符串传递，避免大整数经过 Lua number 后失真。

库存必须先由数据库快照显式预热。重复预热不覆盖已存在的数据；残缺 key 不自动重建。requestId 在活动内绑定 userId，重放返回原记录，不延长预占时间。同用户使用另一个 requestId 返回 DUPLICATE。

## 为什么不采用其他方案

继续仅用 MySQL 无法分离准入层的成本和瓶颈。分布式锁增加了状态和往返次数，而当前临界区可由一段短脚本完成。分桶需要基准证明瓶颈，当前不进行分桶或 Cluster 吞吐优化。

## 风险与代价

- [Redis Lua 原子执行](https://redis.io/docs/latest/develop/interact/programmability/eval-intro/)会阻塞其他命令；脚本仅包含短临界区，不做全量扫描。运行时错误不意味着已执行写入自动回滚，因此先检查 key 类型、数字及关联状态。
- `expireAt` 是后续扫描依据，四种 key 当前均无自动 TTL。删除 PENDING 会失去补偿依据；保留记录则会消耗内存。阶段 3/5 必须实现状态收敛后才能讨论终态清理。
- AOF 和 noeviction 是本地复现配置，不能替代 durable reservation 或故障恢复。Redis 丢失状态后不能直接用 DB availableStock 覆盖在线库存。
- 单活动 key 属于一个 Cluster slot，方便原子操作，但不能因此声称已验证 Cluster，或声称同节点多 key 获得并行吞吐。
- 活动内 requestId 与阶段 1 订单表的全局 request_id 唯一约束不同。阶段 3 接口整合必须明确处理此差异。
- 该组件尚未接到 HTTP 下单路径。同一活动不能混用阶段 1 下单和独立预占测试后再将两个库存数字相加。

## 如何验证

真实 Redis 测试覆盖 1000 请求/100 库存、100 次请求重放、用户重复竞争、活动隔离、时间窗、大小写、大整数、重复预热、状态损坏与脚本缓存清空。仅据实际报告填写结果；请求数量不等于同时在途请求数，更不等于性能指标。

[Spring Data Redis 3.5 的脚本执行器](https://docs.spring.io/spring-data/redis/reference/3.5/redis/scripting.html)先尝试 EVALSHA，并在缓存缺失时回退到 EVAL；测试应覆盖脚本缓存清空后的行为。

## 何时重审

阶段 3 接入 durable reservation/MQ 时评审幂等作用域与失败补偿；阶段 5 实现超时扫描、对账与终态清理时评审保留期；阶段 7 在基准显示真实热点后评审分桶。
