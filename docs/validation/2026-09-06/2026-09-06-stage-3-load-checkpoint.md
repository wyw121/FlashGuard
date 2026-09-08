# 2026-09-06 阶段3 压测检查记录（Smoke + Full）

> 记录时间：2026-09-06  
> 机器：D:\code\flashguard-workbench\flashguard  
> 应用地址：`http://localhost:8080`  
> 活动 ID：`1001`（活动状态设为进行中，库存 `100`）

## 1. 记录目的
- 判断当前阶段3压测链路是否可运行。
- 按“先功能预检，再压测，再指标解读”顺序留存可复现证据。
- 区分脚本/工具运行状态与业务正确性。

## 2. 前置环境与启动要点
- 依赖
  - `docker compose -f compose.yml up -d --wait mysql`
  - 如需 Redis：`docker compose -f compose.yml --profile redis up -d --wait redis`
- 启动应用
  - `mvn spring-boot:run`
- 活动初始化（防止活动过期导致全部失败）
  ```sql
  DELETE FROM orders WHERE activity_id = 1001;
  DELETE FROM activity_stock WHERE activity_id = 1001;
  DELETE FROM activity WHERE id = 1001;

  INSERT INTO activity(id, name, start_time, end_time, status)
  VALUES (1001, 'Quick Start', UTC_TIMESTAMP(6) - INTERVAL 1 HOUR,
          UTC_TIMESTAMP(6) + INTERVAL 1 HOUR, 'ACTIVE');

  INSERT INTO activity_stock(activity_id, initial_stock, available_stock, version)
  VALUES (1001, 100, 100, 0);
  ```

## 3. 本次执行命令
- 烟雾检查（K6）：  
  ```powershell
  k6 run -e K6_MODE=smoke -e BASE_URL=http://localhost:8080 -e ACTIVITY_ID=1001 src\test\k6\phase3-participation.js
  ```
- 正式压测：  
  ```powershell
  k6 run -e K6_MODE=full -e BASE_URL=http://localhost:8080 -e ACTIVITY_ID=1001 -e USER_PREFIX=u_p3 src\test\k6\phase3-participation.js
  ```
- 注意：之前命令里出现的 `[http://localhost:8080](http://localhost:8080)` 是 markdown 链接格式，不适用于终端命令。

## 4. 烟雾检查结果（K6_MODE=smoke）
- 80 次迭代，10 VUs
- `checks`：`100.00%`（160/160）
- `http_req_failed`：`0.00%`
- 延迟指标：
  - `p(95)<1500ms`：`313.2ms`（通过）
  - `p(99)<3000ms`：`330.75ms`（通过）
- 说明：该阶段链路可运行，端到端请求流程没有阻断错误。

## 5. 全量压测结果（K6_MODE=full）
- 执行时间：`2m30.0s`
- 迭代数：`38554`
- `http_reqs`：`38554`
- `checks`：`100.00%`（77108/77108）
- 业务码相关计数：
  - `business_failure_count=38535`（主要为 `SOLD_OUT`/限流后的重复拒绝类业务返回）
- 错误指标：
  - `http_req_failed = 99.95%`（`38535/38554`）
  - `pending_count = 0`
- 延迟：
  - `p(95)=1.3s`（阈值 1.0s，不通过）
  - `p(99)=2.22s`（阈值 2.0s，不通过）
- 执行提示：
  - `WARN Insufficient VUs, reached 300 active VUs and cannot initialize more`

## 6. 结果判断（这次是否“合格”）
结论：**当前结果不算完整合格**。  
原因如下：
1. 全量压测 `http_req_failed` 被触发为 99.95%，但该项目当前请求中大量 `409` 是业务可接受码（如 `SOLD_OUT` / `DUPLICATE_PURCHASE`），目前脚本把它们在 `check` 里算为可接受。  
2. `p(95)` 与 `p(99)` 未通过当前阈值。  
3. 启动/调度提示 `Insufficient VUs`，说明压测模型在高压阶段已达到 VU 与吞吐瓶颈，当前配置可能过激。

## 7. 建议的优化后续（优先）
1. 调低 full 模式注入速率（例如 `K6_START_RATE` 降低）并提高预热库存（避免绝大多数请求立刻 `SOLD_OUT`）。  
2. 将全量压测阈值聚焦于“业务可接受成功”：
   - 不再将 409 直接计入 `http_req_failed` 的失败判定阈值；
   - 以 `check` 或自定义业务码指标作为主判定。
3. 对比两类测试：
   - `smoke`：用于脚本与服务可达性；
   - `full`：用于容量/延迟曲线，应单独给出“业务成功率、拒绝率、p95/p99、数据库一致性”的分项评估。
4. 需要的话可在脚本中增加 `K6_START_RATE`、`K6_PREALLOC_VUS`、`K6_MAX_VUS` 可配置项，避免每次修改脚本。

## 8. 记录状态
- 本次记录已落盘：  
  - [2026-09-06-stage-3-load-checkpoint.md](2026-09-06/2026-09-06-stage-3-load-checkpoint.md)  
- 下次重复执行时，直接沿用“2.前置环境”与“3.本次执行命令”即可复现。
