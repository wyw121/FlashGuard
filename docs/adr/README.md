# 设计决策索引

| ADR | 决策与适用范围 |
| --- | --- |
| [001 Java 17 / Spring Boot 3](001-use-java17-boot3.md) | 统一运行与构建基线 |
| [002 MySQL 原子条件更新](002-atomic-sql-baseline.md) | 同步基线的事务边界与唯一约束 |
| [003 Redis Lua 预占](003-redis-lua-reservation.md) | 单活动原子检查、扣减、幂等，PENDING 不等于订单 |
| [004 durable reservation](004-stock-reservation-durable-state.md) | 持久化预占与状态迁移 |
| [005 消费幂等](005-order-confirmation-consumer-idempotent.md) | 库存、订单、确认状态在同一 MySQL 事务提交 |
| [006 缓存与限流](006-rate-limit-and-activity-cache.md) | 本地 / 全局限流、Caffeine / Redis；当前不增加熔断器 |
| [007 补偿与对账](007-reservation-compensation-and-reconciliation.md) | 幂等归还、孤儿恢复与持久化差异 |
| [008 真实 RocketMQ 与取消栅栏](008-real-rocketmq-transaction-fence.md) | half message、本地事务、回查及迟到提交防护 |
| [009 库存分桶](009-inventory-bucket-decision.md) | 依据三主节点五轮实测，当前不启用生产分桶 |

004/005 记录基础模型，真实 MQ 接入及其恢复边界以 008 和最新[验收台账](../validation/2026-09-13/stages-1-8-acceptance.md)为准。容量、故障窗口与实验环境不能仅从 ADR 的设计意图推断，须查看对应原始结果。
