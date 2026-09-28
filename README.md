# FlashGuard

FlashGuard 是一个面向秒杀场景的营销库存与订单后端。目前包含 MySQL 同步基线、Redis Lua 预占、durable reservation、事务内幂等消费者，以及超时补偿、Redis 孤儿预占恢复和持久化对账。数据库是最终订单事实；`PENDING` 不代表订单完成。

**当前进度（2026-09-28 核对）：** 阶段 1–8 的后端主链路、缓存限流、补偿对账、可观测性与六类真实故障已有实现和验证。2026-09-17 的 8 个 HTTP 场景均完成 60 秒预热、180 秒稳态 × 5 轮，在配置的 10 requests/s 下 PASS；本轮新增同步/异步混合用户场景各五轮 PASS（异步待机中断失败及接电重跑分开保留）。这不是最大容量。百万历史订单 SQL 实验已完成；三主节点五轮 A/B 未证明分桶有稳定收益，因此生产路径保留单热点。本轮还补齐独立克隆脚本与临时防休眠验证入口。

| 交付证据 | 入口 |
| --- | --- |
| 阶段进度与验收边界 | [阶段 1–8 台账](docs/validation/2026-09-13/stages-1-8-acceptance.md) |
| 本轮补充与验证 | [2026-09-28 执行记录](docs/validation/2026-09-28-completion.md) |
| HTTP 协议、原始数据与复现 | [benchmark](benchmark/README.md) |
| 百万订单数据集、索引与深分页 | [SQL 实验](docs/validation/2026-09-13/million-orders-sql.md) |
| 单热点 / Cluster 分桶取舍 | [五轮 A/B](docs/validation/2026-09-13/stage7-cluster-ab.md) |
| 故障注入与恢复 | [六类真实故障](docs/failure-cases/README.md) |
| 设计决策 | [ADR 001–009](docs/adr/README.md) |
| 请求流与项目讲述 | [结合代码的讲解](docs/interview-notes/project-walkthrough.md) |

交付面为后端 HTTP API、测试与运行脚本，不包含前端商城或管理控制台。历史记录保留当时的状态；新增验证不会覆盖失败轮次。

## 阶段 1 范围与非目标

阶段 1 基线包含（下方保留该阶段的运行说明）：

- 查询活动详情及数据库实时库存；
- 参加活动，并在同一事务内扣减库存、同步创建订单；
- 使用 `requestId` 重放请求和查询参加结果；
- 使用 `orderNo` 查询订单；
- Flyway 建表、MySQL 原子条件更新、数据库唯一约束；
- MVC、业务单元、MySQL 集成与 1000 请求/100 库存并发测试；
- 固定为 `mysql:8.4.8` 的 Docker Compose 配置和 PowerShell 7 基线脚本。

HTTP 使用显式 `FLASHGUARD_PARTICIPATION_MODE=mysql|async`，默认保留同步 MySQL 基线。`async` 还需要 `FLASHGUARD_MQ_ENABLED=true`、可连接的 Broker 和已预热活动；Redis 未就绪/不可用返回 503。异步预占返回 `PENDING`，真实 MQ 消费事务完成后结果查询返回 `CONFIRMED`。活动详情使用缓存，补偿与对账直接使用数据库事实。

## 阶段 2：Redis Lua 原子预占

- 显式从 MySQL 可售库存快照预热，重复预热不会覆盖已扣减的 Redis 库存；
- 一段 Lua 原子完成 requestId 幂等、userId 重复预检、库存检查与扣减、PENDING 记录；
- 明确返回 `SUCCESS / SOLD_OUT / DUPLICATE / REPLAY`，并区分未预热、活动时间窗和请求身份冲突；
- 按活动与 requestId 查询原预占记录；重放保持原 reservationId 和过期时间；
- 同一活动的 key 共用 hash tag，保留 PENDING 和超时扫描索引；当前均不设置自动 TTL。

仅启动 Redis 依赖（MySQL 基线启动命令不受影响）：

```powershell
docker compose --profile redis up -d --wait redis
```

Redis 镜像锁定为 `redis:7.4.8`。键结构、状态保留、Java 调用方式及测试命令见 [Redis 预占说明](docs/architecture/redis-reservation.md)，取舍见 [ADR 003](docs/adr/003-redis-lua-reservation.md)，实际验收状态见 [本轮验证记录](docs/validation/2026-09-06-stage-2.md)。

异步模式通过 MQ 创建订单，独立超时扫描处理补偿。对同一活动切换同步/异步模式前应停止新流量、排空消息并完成对账，使用新活动进行模式对照实验。

## 架构

项目采用 Java 17、Spring Boot 3.5.16、MyBatis 和 Flyway，按业务能力分包：

```text
HTTP
  -> 校验 / Caffeine + Redis 活动缓存 / 活动与用户限流
  -> ParticipationService（显式模式）
     mysql -> ParticipationTransaction -> MySQL
     async -> Redis Lua 预占 -> RocketMQ half message
           -> 本地 MySQL durable reservation 事务 -> COMMIT
           -> MQ Consumer -> MySQL 订单/库存/CONFIRMED 同一事务
  -> 异常：Broker 回查 / retry / DLQ / 超时与孤儿扫描 / 对账
```

- `activity`：活动与库存联合查询；
- `participation`：参加活动、幂等重放、事务编排；
- `inventory`：带库存条件的单条扣减 SQL；
- `order`：订单写入和按请求、用户或订单号查询；
- `shared`：统一响应、业务错误和订单号生成；
- `src/main/resources/db/migration`：数据库结构的唯一来源。

所有接口都返回 `{ "code", "message", "data" }`。Controller 负责 HTTP 映射，Service 定义业务与事务语义，数据库负责最终并发正确性；非预期异常只向客户端返回 `INTERNAL_ERROR`，详细异常保留在服务日志中。

## 参加活动的请求生命周期

`POST /api/v1/activities/{activityId}/participations` 在 `mysql` 基线模式按以下顺序执行；异步模式见 [事务消息设计](docs/adr/008-real-rocketmq-transaction-fence.md)：

1. Bean Validation 校验正数 `userId`，以及非空且最长 64 字符的 `requestId`。
2. 在事务中按 `requestId` 查询订单；已存在时不再扣库存，返回 HTTP 200 和 `REPLAY`。
3. 查询活动，并以 UTC 校验活动状态、开始时间和结束时间。
4. 执行一条带 `available_stock > 0` 条件的 `UPDATE`；未更新任何行时返回 `SOLD_OUT`。
5. 写入状态为 `CONFIRMED` 的订单；正常创建返回 HTTP 201 和 `SUCCESS`。
6. 并发插入命中唯一键时，整个事务（包括刚才的扣减）回滚；事务外重新查询后，将相同请求映射为 `REPLAY`，将同活动同用户映射为 `DUPLICATE_PURCHASE`。

活动、参加结果和订单查询分别通过活动 ID、`requestId` 和 `orderNo` 读取当前已提交数据。

## 数据库不变量

| 不变量 | 数据库与事务保证 |
| --- | --- |
| 库存不为负 | `available_stock` 为无符号整数；扣减 SQL 同时要求 `available_stock > 0`。 |
| 可用库存不超过初始库存 | `CHECK (available_stock <= initial_stock)`。 |
| 一人一活动最多一单 | `UNIQUE (activity_id, user_id)`；重复插入导致事务回滚。 |
| 同一请求只产生一个结果 | `request_id` 唯一；重放返回已有订单且不再次扣库存。 |
| 对外订单号唯一 | `order_no` 唯一，生产实现生成 26 字符的时间前缀加安全随机后缀。 |
| 活动与订单引用有效 | 库存、订单均通过外键引用活动；活动时间窗和枚举状态由检查约束限制。 |

## 先决条件

以下命令以 Windows 上的 PowerShell 7 和项目根目录为准：

- JDK 17 或更高版本；源码和字节码目标固定为 Java 17。本机验证使用 Oracle JDK 21.0.9 LTS；
- Maven 3.9.10 或兼容版本；
- PowerShell 7；基线脚本已使用 PowerShell 7.6.5 验证，应通过 `pwsh` 运行；
- Docker Desktop，且 `docker version` 必须同时显示 Client 和 Server；
- 可访问容器镜像仓库，以便首次获取正式目标 `mysql:8.4.8`；
- 默认端口 `3306` 和 `8080` 未被占用。

常用配置均可通过环境变量覆盖：

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `FLASHGUARD_MYSQL_PORT` | `3306` | Compose 映射到宿主机的 MySQL 端口 |
| `FLASHGUARD_MYSQL_DATABASE` | `flashguard` | Compose 初始化数据库 |
| `FLASHGUARD_MYSQL_ROOT_PASSWORD` | `flashguard-root-dev` | Compose 开发环境 root 密码 |
| `FLASHGUARD_DB_URL` | `jdbc:mysql://localhost:3306/flashguard?...` | 应用 JDBC 地址 |
| `FLASHGUARD_DB_USERNAME` | `flashguard` | Compose 用户及应用用户名 |
| `FLASHGUARD_DB_PASSWORD` | `flashguard-dev` | Compose 用户及应用密码 |
| `FLASHGUARD_DB_POOL_SIZE` | `20` | Hikari 最大连接数 |
| `FLASHGUARD_PORT` | `8080` | HTTP 端口 |

这些密码只是本地开发默认值，不适用于共享或生产环境。若修改宿主机 MySQL 端口，还必须同步修改 `FLASHGUARD_DB_URL`。

## 启动

先校验 Compose 配置并启动 MySQL：

```powershell
docker compose config
docker compose up -d --wait mysql
```

再在同一目录启动应用。Flyway 会在连接到空库时自动执行迁移：

```powershell
mvn spring-boot:run
```

应用会占用当前终端。以下命令请在第二个 PowerShell 窗口中执行。

迁移默认不写入演示数据；可用下面的可重复脚本准备活动 `1001` 和 10 件库存：

```powershell
@'
DELETE FROM orders WHERE activity_id = 1001;
DELETE FROM activity_stock WHERE activity_id = 1001;
DELETE FROM activity WHERE id = 1001;
INSERT INTO activity(id, name, start_time, end_time, status)
VALUES (1001, 'Quick Start', UTC_TIMESTAMP(6) - INTERVAL 1 HOUR,
        UTC_TIMESTAMP(6) + INTERVAL 1 HOUR, 'ACTIVE');
INSERT INTO activity_stock(activity_id, initial_stock, available_stock, version)
VALUES (1001, 10, 10, 0);
'@ | docker compose exec -T mysql sh -c 'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"'
```

## 四个 `curl.exe` 示例

下面四条命令依次查询活动、参加活动、按请求查询参加结果、按响应中的真实订单号查询订单：

```powershell
# 1. 查询活动
curl.exe -sS http://localhost:8080/api/v1/activities/1001

# 2. 参加活动，并保留响应中的订单号
$participation = curl.exe -sS -X POST `
  -H 'Content-Type: application/json' `
  --data-raw '{"userId":10001,"requestId":"quickstart-request-001"}' `
  http://localhost:8080/api/v1/activities/1001/participations | ConvertFrom-Json
$participation

# 3. 按 requestId 查询参加结果
curl.exe -sS http://localhost:8080/api/v1/participations/quickstart-request-001

# 4. 按参加响应中的 orderNo 查询订单
$orderNo = $participation.data.orderNo
curl.exe -sS "http://localhost:8080/api/v1/orders/$orderNo"
```

再次执行同一个 POST 会返回 HTTP 200、业务码 `REPLAY` 和原订单；换用新的 `requestId` 但保持 `userId=10001`，会返回 HTTP 409 和 `DUPLICATE_PURCHASE`。

使用 Postman 时，参与请求的方法选择 `POST`，Body 选择 `raw` → `JSON`，请求头为 `Content-Type: application/json`。默认地址为 `http://localhost:8080/api/v1/activities/1001/participations`；若设置了 `FLASHGUARD_PORT=18080`，地址端口也要改成 `18080`。

```json
{"userId":10001,"requestId":"postman-001"}
```

参与地址使用 GET 会返回 `405 METHOD_NOT_ALLOWED`（`Allow: POST`）；发送 Text 或表单格式会返回 `415 UNSUPPORTED_MEDIA_TYPE`，响应 Accept 头列出支持的格式。这些属于请求错误。真正的服务器异常仍返回 `500 INTERNAL_ERROR`，具体原因保留在应用日志中。

停止应用后，可保留数据卷关闭 Compose：

```powershell
docker compose down
```

## 测试

完整验证包含专属 `mysql:8.4.8`、`redis:7.4.8` 与真实 RocketMQ 5.3.2 测试环境。需要 Docker Desktop Linux engine、Java 17+、Maven；首次运行真实 MQ 场景可使用下文包装器，自动检查并构建隔离的 Broker Java 运行镜像。

```powershell
mvn clean verify
```

只运行不依赖 Docker 的上下文、业务单元和 MVC 测试：

```powershell
mvn '-Dtest=FlashGuardApplicationTest,ParticipationServiceTest,TimeRandomOrderNumberGeneratorTest,ActivityControllerTest,ParticipationControllerTest,OrderControllerTest,ReservationWarmupServiceTest' test
```

只运行两个真实 MySQL 测试类：

```powershell
mvn '-Dtest=ActivityQueryServiceTest,ParticipationMySqlIntegrationTest' test
```

只运行阶段 2 的 Redis 预占与预热测试：

```powershell
mvn '-Dtest=RedisReservationStoreTest,ReservationWarmupServiceTest' test
```

外部测试数据库的回退方式及本机观测结果见 [`docs/environment.md`](docs/environment.md)。本地 MySQL 9.2 回退不替代正式的 `mysql:8.4.8` 验收。

## 真实故障复现

六个[故障包装器与场景说明](docs/failure-cases/README.md)自建专属组件、随机 Topic/组和活动，不要求已运行应用或活动 1001。需要 Docker Desktop Linux engine、Java 17+、Maven、PowerShell 7。工作台 Maven settings 存在时自动沿用；独立克隆使用 Maven 默认配置，也可在 `verify-real-chain.ps1` 传入 `-MavenSettings`。例如：

```powershell
pwsh -NoProfile -File 'D:\code\flashguard-workbench\flashguard\scripts\failure-cases\inject-mysql-slow-or-down.ps1'
```

2026-09-13 保留的[真实 MQ 结果](docs/validation/2026-09-13/real-mq/results.json)为 11 项全部通过、0 失败/错误/跳过，约 357.7 秒。包括真实 Broker 回查、100 次消息投递幂等、专属 MySQL/Redis 暂停恢复、DLQ、Producer 两个明确边界退出、Consumer 业务事务前退出重投，以及 Broker 积压 20 → 0。Consumer 的退出点在调用 JDBC 业务事务之前，不是 JDBC commit 中途；该套件通过不代表全项目 verify 或性能容量验收完成。

`flashguard_mq_backlog` 采样真实 Broker 偏移差，不可用为 -1；`flashguard_reservation_backlog` 只统计 DB 中 PENDING/MQ_COMMITTED 业务记录。两者分别观察，不能用 DB 预占归零代替 Broker 排空证据。

## 并发基线脚本

先按“启动”一节运行 Compose MySQL 和应用，再在第二个 PowerShell 中执行：

```powershell
pwsh -NoProfile -File .\scripts\check-baseline.ps1 `
  -BaseUrl http://localhost:8080 `
  -StartupTimeoutSeconds 60
```

脚本使用 `SocketsHttpHandler` 建立 HTTP 客户端，会删除并重建专用活动 `900000001`，固定并行发送 1000 个不同用户和不同请求标识来竞争 100 件库存，最后直接查询 Compose 中的数据库。成功条件及输出为：

```text
PASS SUCCESS=100 SOLD_OUT=900 orders=100 stock=0
```

脚本仅接受 `BaseUrl` 和 `StartupTimeoutSeconds` 两个参数。它会改写活动 `900000001` 及其关联订单，且要求数据库就是当前 `compose.yml` 中名为 `mysql` 的服务；不要对包含同 ID 业务数据的环境运行它。

## 当前限制

- 2026-09-06 已补齐 MySQL 8.4.8 的 Compose、Flyway、Testcontainers 和 HTTP 基线验证；本机 Docker daemon 拉取仍可能报 EOF，本轮通过宿主机下载并校验官方镜像后导入，来源与 digest 见环境文档；
- Redis 7.4.8 已用于独立组件验证和 Compose 健康检查；正确性测试结果不代表可持续吞吐或生产容量；
- 数据库迁移不包含业务种子数据，Quick Start 必须显式准备活动；
- 参与接口默认选择明确的 `mysql` 同步基线；启用 `async` 模式与真实 MQ 后走 Redis 预占、half message、本地 durable 事务、真实 Consumer 与补偿链路。异步依赖不可用时拒绝请求，不隐式回退到同步下单；
- 真实 MQ 功能与指定故障场景已有上述实测；HTTP 压测、百万数据与 Cluster 实验各有独立协议和报告，不能用消息功能测试代替其验收；
- 默认密码、单节点 Compose 和基线脚本仅用于本地开发，未做生产安全与容量配置。
