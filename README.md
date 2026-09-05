# FlashGuard

FlashGuard 是一个面向秒杀场景的 MySQL 正确性基线。当前阶段用一个可运行、可测试的模块化单体证明三件事：库存不会被扣成负数、同一用户在同一活动中最多生成一张订单、业务拒绝与系统错误通过稳定的 HTTP 契约区分。

## 阶段 1 范围与非目标

当前实现包含：

- 查询活动详情及数据库实时库存；
- 参加活动，并在同一事务内扣减库存、同步创建订单；
- 使用 `requestId` 重放请求和查询参加结果；
- 使用 `orderNo` 查询订单；
- Flyway 建表、MySQL 原子条件更新、数据库唯一约束；
- MVC、业务单元、MySQL 集成与 1000 请求/100 库存并发测试；
- 固定为 `mysql:8.4.8` 的 Docker Compose 配置和 PowerShell 7 基线脚本。

当前明确不实现 Redis、RocketMQ、异步下单、定时补偿、分布式限流、缓存、支付、物流、购物车、用户中心、微服务拆分或熔断器。这些后续能力不得削弱本阶段已经定义的数据库不变量。

## 架构

项目采用 Java 17、Spring Boot 3.5.16、MyBatis 和 Flyway，按业务能力分包：

```text
HTTP
  -> activity / participation / order Controller
  -> 查询 Service 或 ParticipationService
  -> ParticipationTransaction（事务边界）
  -> MyBatis Mapper（显式 SQL）
  -> MySQL（最终并发约束）
```

- `activity`：活动与库存联合查询；
- `participation`：参加活动、幂等重放、事务编排；
- `inventory`：带库存条件的单条扣减 SQL；
- `order`：订单写入和按请求、用户或订单号查询；
- `shared`：统一响应、业务错误和订单号生成；
- `src/main/resources/db/migration`：数据库结构的唯一来源。

所有接口都返回 `{ "code", "message", "data" }`。Controller 负责 HTTP 映射，Service 定义业务与事务语义，数据库负责最终并发正确性；非预期异常只向客户端返回 `INTERNAL_ERROR`，详细异常保留在服务日志中。

## 参加活动的请求生命周期

`POST /api/v1/activities/{activityId}/participations` 按以下顺序执行：

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

停止应用后，可保留数据卷关闭 Compose：

```powershell
docker compose down
```

## 测试

完整验证命令如下；默认由 Testcontainers 启动固定标签的 `mysql:8.4.8`：

```powershell
mvn clean verify
```

只运行不依赖 Docker 的上下文、业务单元和 MVC 测试：

```powershell
mvn '-Dtest=FlashGuardApplicationTest,ParticipationServiceTest,TimeRandomOrderNumberGeneratorTest,ActivityControllerTest,ParticipationControllerTest,OrderControllerTest' test
```

只运行两个真实 MySQL 测试类：

```powershell
mvn '-Dtest=ActivityQueryServiceTest,ParticipationMySqlIntegrationTest' test
```

外部测试数据库的回退方式及本机观测结果见 [`docs/environment.md`](docs/environment.md)。本地 MySQL 9.2 回退不替代正式的 `mysql:8.4.8` 验收。

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

- 截至 2026-09-05，本机 Docker Desktop 和 Engine 已恢复，但 `mysql:8.4.8` 拉取因镜像仓库连接 `unexpected EOF` 尚未完成，因此本文不宣称 Compose、Flyway 或 Testcontainers 已在该目标镜像上通过；
- 数据库测试已使用独立的本机 MySQL 9.2.0 测试库回退验证，但 9.2.0 不是项目正式运行或验收目标；
- 数据库迁移不包含业务种子数据，Quick Start 必须显式准备活动；
- 当前下单是同步数据库事务基线，尚未覆盖跨服务、消息投递、缓存一致性或故障补偿；
- 默认密码、单节点 Compose 和基线脚本仅用于本地开发，未做生产安全与容量配置。
