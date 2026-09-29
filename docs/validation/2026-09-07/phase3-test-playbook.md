# FlashGuard 阶段3验证手册（2026-09-07）

> 目标：在本地一次性确认“阶段3代码是否可运行 → 功能验证通过 → 压测可执行且指标可解释”。  
> 命令默认在 `PowerShell` 下运行，当前项目路径：`D:\code\flashguard-workbench\flashguard`

## 0）先决条件
- Java 17
- Maven
- Docker Desktop
- k6（`k6 version` 可用）
- 已知项目根目录：`D:\code\flashguard-workbench\flashguard`

## 1）路径问题先统一（防止新开终端执行失败）
- 任何命令在新终端先执行：
```powershell
cd D:\code\flashguard-workbench\flashguard
```
- 验证当前路径：
```powershell
Get-Location
```
- 如果你不想每次 cd，也可以把 compose 指令改成绝对路径：
```powershell
$compose = 'D:\code\flashguard-workbench\flashguard\compose.yml'
```

## 2）服务启动与依赖
```powershell
cd "D:\\code\\flashguard-workbench\\flashguard"
docker compose -f compose.yml up -d --wait mysql
```

可选启动 Redis（如果你需要）：
```powershell
docker compose -f compose.yml --profile redis up -d --wait redis
```

## 3）数据库准备（必须执行；避免活动过期导致压测全量失败）
活动种子准备命令（必须在服务启动后、应用启动前执行）：

```powershell
cd D:\code\flashguard-workbench\flashguard
$seedSql = @"
DELETE FROM orders WHERE activity_id = 1001;
DELETE FROM activity_stock WHERE activity_id = 1001;
DELETE FROM activity WHERE id = 1001;

INSERT INTO activity(id, name, start_time, end_time, status)
VALUES (1001, 'Quick Start', UTC_TIMESTAMP(6) - INTERVAL 1 HOUR,
        UTC_TIMESTAMP(6) + INTERVAL 1 HOUR, 'ACTIVE');

INSERT INTO activity_stock(activity_id, initial_stock, available_stock, version)
VALUES (1001, 100, 100, 0);
"@
$seedSql | docker compose -f compose.yml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u"$MYSQL_USER" "$MYSQL_DATABASE"'
```

如果你在任意目录执行（推荐在新终端偶有路径不对时用）：
```powershell
$compose = 'D:\code\flashguard-workbench\flashguard\compose.yml'
$seedSql = @"
DELETE FROM orders WHERE activity_id = 1001;
DELETE FROM activity_stock WHERE activity_id = 1001;
DELETE FROM activity WHERE id = 1001;

INSERT INTO activity(id, name, start_time, end_time, status)
VALUES (1001, 'Quick Start', UTC_TIMESTAMP(6) - INTERVAL 1 HOUR,
        UTC_TIMESTAMP(6) + INTERVAL 1 HOUR, 'ACTIVE');

INSERT INTO activity_stock(activity_id, initial_stock, available_stock, version)
VALUES (1001, 100, 100, 0);
"@
$seedSql | docker compose -f $compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u"$MYSQL_USER" "$MYSQL_DATABASE"'
```

## 4）服务层代码编译与单测

### 4.1 编译（无测试）
```powershell
cd "D:\\code\\flashguard-workbench\\flashguard"
mvn -q -DskipTests compile
```

### 4.2 阶段3核心测试（两个命令）
```powershell
cd "D:\\code\\flashguard-workbench\\flashguard"
mvn -q -Dtest="ParticipationServiceTest,ParticipationControllerTest" test
mvn -q -Dtest="ParticipationMySqlIntegrationTest" test
```

### 4.3 可选：整库测试
```powershell
mvn -q test
```

## 5）启动应用（单独终端）
```powershell
cd "D:\\code\\flashguard-workbench\\flashguard"
mvn spring-boot:run
```

应用日志出现 `Started` 才说明可用于压测。

建议将 `mvn spring-boot:run` 保持运行在**终端A**，其余测试命令在**终端B**执行。

## 6）Smoke 预检（正式压测前必须）
```powershell
cd "D:\\code\\flashguard-workbench\\flashguard"
k6 run -e K6_MODE=smoke -e BASE_URL=http://localhost:8080 -e ACTIVITY_ID=1001 -e K6_SMOKE_VUS=10 -e K6_SMOKE_ITERATIONS=80 -e K6_HTTP_REQ_FAILED_STRICT=true src\test\k6\phase3-participation.js
```

通过条件：  
- `checks` >= `0.90`  
- `http_req_failed`（smoke 严格模式） < `0.10`  
- `http_req_duration p95 < 1500ms`, `p99 < 3000ms`
- `system_error_rate < 5%`

## 7）Full 压测（Smoke 通过后执行）
```powershell
cd "D:\\code\\flashguard-workbench\\flashguard"
k6 run -e K6_MODE=full -e BASE_URL=http://localhost:8080 -e ACTIVITY_ID=1001 -e USER_PREFIX=u_p3 -e K6_START_RATE=220 -e K6_PREALLOC_VUS=300 -e K6_MAX_VUS=300 -e K6_TARGET=220 -e K6_STAGE_UP=30s -e K6_STAGE_STEADY=90s -e K6_STAGE_DOWN=30s -e K6_HTTP_REQ_FAILED_STRICT=false src\test\k6\phase3-participation.js
```

建议先把 `K6_START_RATE` 从 `220` 降到 `120`，观察 `Insufficient VUs` 与延迟是否明显改善，再逐步加压。

说明：`K6_HTTP_REQ_FAILED_STRICT=true` 建议仅在 smoke/链路稳定阶段使用；full 场景优先看 `system_error_rate`，不再把 `409` 视为系统失败计入 `http_req_failed`。

## 8）结果判定（阶段3）
### 通过（PASS）
- 编译和核心测试通过（第4步）
- smoke 按上面阈值通过（第6步）
- full 中 `checks` >= `95%`
- `http_req_failed`：仅 smoke 严格口径下 `< 10%`，full 场景以 `system_error_rate` 为主
- `system_error_rate < 1%`
- `transport_failure_count < 10`
- `http_req_duration p95 < 2500ms`, `p99 < 3500ms`

重点看：
- `business_failure_count` 与 `business_reject_rate` 是否由 `SOLD_OUT`/`DUPLICATE_PURCHASE` 占主导；
- `system_error_count`/`system_error_rate` 是否接近 0；
- 是否持续出现 `Insufficient VUs`，持续出现就应先降压再谈延迟指标。

### 复核（REVIEW）
- `business_failure_count` 很高但 `transport_failure_count` 与 `system_error_rate` 很低（说明业务限流/售罄行为合理）
- 持续出现 `Insufficient VUs`，表示压测模型在该速率下已经到达瓶颈
- `http_req_failed` 若非烟雾场景严格口径触发，需优先确认自定义指标而非“409即失败”

### 不合格（FAIL）
- 任何一条编译或测试报错
- smoke 失败
- full 出现大量系统错误 (`system_error_rate` 超标) 或 transport 失败

## 9）完整“新终端可复现链路”（建议直接复制执行）
```powershell
# 终端A（服务）
cd "D:\\code\\flashguard-workbench\\flashguard"
docker compose -f compose.yml up -d --wait mysql
$seedSql = @"
DELETE FROM orders WHERE activity_id = 1001;
DELETE FROM activity_stock WHERE activity_id = 1001;
DELETE FROM activity WHERE id = 1001;
INSERT INTO activity(id, name, start_time, end_time, status)
VALUES (1001, 'Quick Start', UTC_TIMESTAMP(6) - INTERVAL 1 HOUR,
        UTC_TIMESTAMP(6) + INTERVAL 1 HOUR, 'ACTIVE');
INSERT INTO activity_stock(activity_id, initial_stock, available_stock, version)
VALUES (1001, 100, 100, 0);
"@
$seedSql | docker compose -f compose.yml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u"$MYSQL_USER" "$MYSQL_DATABASE"'
mvn -q -DskipTests compile
mvn -q -Dtest="ParticipationServiceTest,ParticipationControllerTest" test
mvn -q -Dtest="ParticipationMySqlIntegrationTest" test
mvn spring-boot:run

# 终端B（压测）
cd "D:\\code\\flashguard-workbench\\flashguard"
k6 run -e K6_MODE=smoke -e BASE_URL=http://localhost:8080 -e ACTIVITY_ID=1001 -e K6_SMOKE_VUS=10 -e K6_SMOKE_ITERATIONS=80 -e K6_HTTP_REQ_FAILED_STRICT=true src\test\k6\phase3-participation.js
k6 run -e K6_MODE=full -e BASE_URL=http://localhost:8080 -e ACTIVITY_ID=1001 -e USER_PREFIX=u_p3 -e K6_START_RATE=220 -e K6_PREALLOC_VUS=300 -e K6_MAX_VUS=300 -e K6_TARGET=220 -e K6_STAGE_UP=30s -e K6_STAGE_STEADY=90s -e K6_STAGE_DOWN=30s -e K6_HTTP_REQ_FAILED_STRICT=false src\test\k6\phase3-participation.js
```

### 说明
- 你可以在终端B执行 `Get-Location` 确保已到项目目录；若不是，立即 `cd "D:\\code\\flashguard-workbench\\flashguard"` 再继续。

## 10）历史记录
- 阶段性压测记录：[2026-09-06-stage-3-load-checkpoint.md](2026-09-06/2026-09-06-stage-3-load-checkpoint.md)

## 11）阶段4（幂等确认）最小验收命令（不接 MQ 时可先模拟调用服务）

```powershell
cd "D:\\code\\flashguard-workbench\\flashguard"

# 1) 阶段4消费测试（服务层）
mvn -q -Dtest="ReservationConsumerServiceTest" test

# 2) 预置一个可用的 stock_reservation（或跑阶段4测试路径已包含回放/幂等覆盖）
#    目前阶段4服务端点未接 MQ，因此先通过服务层与数据库验收消费幂等收敛。
```
