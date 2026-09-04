# FlashGuard 阶段 0–1 设计说明

## 1. 目标

以只读参考仓库为学习材料，在 `flashguard/` 独立实现一个可运行、可测试、可解释的 MySQL 秒杀基线。首个增量只证明三件事：单条条件更新不会把库存扣成负数，数据库唯一约束能阻止一人多单，所有业务拒绝与系统错误都以稳定接口契约区分。

## 2. 范围

阶段 0 产出参考来源清单、基座调用链阅读笔记和本机环境记录。由于现有三个参考目录都是不含 `.git` 的源码快照，审计必须明确区分“本地快照内容”和“经上游核验的 commit”；在未完成内容比对前不得把上游最新 SHA 宣称为本地快照 SHA。

阶段 1 实现：

- 活动详情查询；
- 参加活动并同步创建基线订单；
- 按请求标识查询参加结果；
- 按订单号查询订单；
- MySQL 原子扣减与同一活动一人一单；
- 固定版本的 MySQL Docker Compose、数据库迁移、自动化测试和 Quick Start。

本阶段不实现 Redis、RocketMQ、定时补偿、分布式限流、缓存、支付、物流、购物车、用户中心、微服务或熔断器。

## 3. 技术与版本约束

- Java 源码与字节码目标：17；本机可使用 JDK 21 执行 Maven。
- Spring Boot：3.5.16。
- MyBatis Spring Boot Starter：3.0.5，使用注解映射承载短而明确的 SQL。
- MySQL：Docker 镜像 `mysql:8.4.8`。
- Testcontainers：2.0.5；集成测试使用真实 MySQL。
- Maven：3.9.10 或兼容版本。
- Flyway 由 Spring Boot 依赖管理；迁移脚本是数据库结构的唯一来源。
- 所有容器镜像使用精确标签，不使用 `latest`。

## 4. 架构

采用单模块、模块化单体。源码按业务能力分包，而不是按全局技术层堆放：

```text
com.flashguard
├── FlashGuardApplication
├── activity
│   ├── ActivityController
│   ├── ActivityQueryService
│   ├── ActivityMapper
│   └── ActivityView
├── participation
│   ├── ParticipationController
│   ├── ParticipationService
│   ├── ParticipationRequest
│   └── ParticipationResult
├── order
│   ├── OrderController
│   ├── OrderQueryService
│   ├── OrderMapper
│   └── OrderView
├── inventory
│   └── ActivityStockMapper
└── shared
    ├── api
    ├── error
    └── id
```

Controller 只负责 HTTP 参数与状态码映射；Service 定义事务边界；Mapper 执行显式 SQL；数据库唯一约束负责最终并发正确性。阶段 2 以后可以在参加活动入口前增加 Redis 预占，但不会改变阶段 1 的数据库不变量。

## 5. HTTP 契约

### 5.1 查询活动

`GET /api/v1/activities/{activityId}`

成功返回 HTTP 200，数据包含活动标识、名称、起止时间、状态、初始库存与当前数据库可用库存。不存在返回 HTTP 404，业务码 `ACTIVITY_NOT_FOUND`。

### 5.2 参加活动

`POST /api/v1/activities/{activityId}/participations`

请求体：

```json
{
  "userId": 10001,
  "requestId": "01J7N8WQ6F2K3M4P5R6S7T8U9V"
}
```

成功返回 HTTP 201，业务码 `SUCCESS`，数据包含 `requestId`、`orderNo` 和状态 `CONFIRMED`。`requestId` 是客户端生成的非空、最长 64 字符标识；阶段 1 在订单表对其建立唯一约束，使同一请求重放返回原有结果而不是再次扣库存。

业务拒绝：

- 活动不存在：HTTP 404，`ACTIVITY_NOT_FOUND`；
- 活动未开始：HTTP 409，`ACTIVITY_NOT_STARTED`；
- 活动已结束或不可用：HTTP 409，`ACTIVITY_ENDED`；
- 库存售罄：HTTP 409，`SOLD_OUT`；
- 同一活动重复购买：HTTP 409，`DUPLICATE_PURCHASE`；
- 同一 `requestId` 重放：HTTP 200，`REPLAY`，返回已有订单。

### 5.3 查询参加结果

`GET /api/v1/participations/{requestId}`

阶段 1 中订单同步创建，因此命中时返回 HTTP 200 和 `CONFIRMED`；未找到返回 HTTP 404，`PARTICIPATION_NOT_FOUND`。后续异步阶段允许返回 `PENDING`、`CONFIRMED` 或 `CANCELLED`，但保持端点不变。

### 5.4 查询订单

`GET /api/v1/orders/{orderNo}`

命中返回 HTTP 200；未找到返回 HTTP 404，`ORDER_NOT_FOUND`。

所有响应统一为 `{ "code", "message", "data" }`。非预期异常返回 HTTP 500 和 `INTERNAL_ERROR`，日志保留异常细节，响应不泄露数据库或堆栈信息。

## 6. 数据模型

### `activity`

- `id BIGINT` 主键；
- `name VARCHAR(128)`；
- `start_time DATETIME(6)`、`end_time DATETIME(6)`；
- `status VARCHAR(24)`，允许 `DRAFT`、`ACTIVE`、`ENDED`；
- `created_at`、`updated_at`。

数据库检查约束保证 `end_time > start_time`。

### `activity_stock`

- `activity_id BIGINT` 主键并关联活动；
- `initial_stock INT UNSIGNED`；
- `available_stock INT UNSIGNED`；
- `version BIGINT UNSIGNED`；
- `updated_at`。

扣减 SQL 固定为：

```sql
UPDATE activity_stock
SET available_stock = available_stock - 1,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE activity_id = #{activityId}
  AND available_stock > 0
```

受影响行数为 0 表示售罄或库存记录不存在，Service 在扣减前已确认活动存在，因此映射为 `SOLD_OUT`。

### `orders`

- `id BIGINT` 自增主键；
- `order_no VARCHAR(32)` 唯一；
- `request_id VARCHAR(64)` 唯一；
- `activity_id BIGINT`；
- `user_id BIGINT`；
- `status VARCHAR(24)`，阶段 1 固定创建为 `CONFIRMED`；
- `created_at`、`updated_at`；
- 唯一索引 `(activity_id, user_id)`。

## 7. 事务与并发语义

`ParticipationService.participate` 在单个 MySQL 事务中执行：

1. 先按 `requestId` 查询已有订单；存在则直接返回 `REPLAY`。
2. 查询活动并按数据库时间语义校验状态和时间窗口。
3. 尝试原子扣减库存；受影响行数为 0 时抛出 `SOLD_OUT`，事务回滚。
4. 插入订单；数据库唯一约束拒绝同活动同用户的并发重复购买。
5. 若订单插入出现重复键，事务整体回滚。事务外重新读取 `requestId` 或 `(activityId,userId)`：前者映射为 `REPLAY`，后者映射为 `DUPLICATE_PURCHASE`。

这样即使两个请求同时越过应用层检查，最多一个事务能提交；失败事务中的库存扣减会随事务回滚。

## 8. 标识生成

服务端订单号使用单调时间前缀加安全随机后缀生成 26 字符字符串，不依赖数据库自增值对外暴露。生成器接口为 `OrderNumberGenerator.next()`，生产实现使用 JDK 时间与 `SecureRandom`，测试注入固定实现以得到可重复断言。

## 9. 测试设计

测试遵循红—绿—重构；每个生产行为先由失败测试定义。

- 纯单元测试：活动时间窗口、错误映射、订单号格式。
- MVC 测试：请求校验、统一响应和 HTTP 状态码。
- Testcontainers MySQL 集成测试：迁移成功、原子扣减、事务回滚、`requestId` 重放、同用户并发重复购买。
- 并发验收：预置库存 100，以 1000 个并发任务和不同用户参加，断言成功订单不超过 100、库存不小于 0、成功订单数等于初始库存。

若 Docker 不可用，纯单元测试仍可运行，但阶段 1 不能宣称通过集成验收。

## 10. 文档与可复现性

- `REFERENCES.md` 记录每个上游 URL、许可证、本地快照状态、经核验 SHA 和实际借鉴边界。
- `docs/architecture/upstream-baseline.md` 记录参考项目的 Controller → Service → DB/Redis/MQ 调用链与 V1–V4 演进。
- `docs/environment.md` 固定 JDK、Maven、Docker、MySQL 与宿主机环境。
- `docs/adr/001-use-java17-boot3.md` 说明版本选择。
- `docs/adr/002-atomic-sql-baseline.md` 说明为何选择单条条件 UPDATE。
- README 给出配置、启动、建表、示例请求和测试命令。

## 11. 验收条件

- Maven 构建在 Java 17 目标下通过。
- MySQL Compose 能健康启动，Flyway 能从空库完成迁移。
- 自动化测试证明库存不会为负、重复用户不会生成第二张订单、请求重放不会再次扣库存。
- 1000 请求/100 库存的并发测试得到 100 张订单、0 个超卖和 0 个重复订单。
- 参考目录没有被修改，所有借鉴均以思想与接口边界重新实现。

