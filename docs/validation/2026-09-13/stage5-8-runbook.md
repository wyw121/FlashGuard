# 阶段5-8验收执行手册（本地单机）

> 目标日期：2026-09-13
> 路径：`D:/code/flashguard-workbench/flashguard`

## 阶段5：超时补偿

1. 触发条件：`stock_reservation` 存在 `PENDING/MQ_COMMITTED` 且 `expire_at <= now`。
2. 关键指标（Prometheus）：
   - `flashguard_reservation_compensated_total`
   - `flashguard_reservation_compensation_failed_total`
3. 一致性检查：
   - `stock_reservation` 仍在超时窗口内的 `PENDING/MQ_COMMITTED` 被回收为 `CANCELLED`
   - 无订单对应时不得再返回 `CONFIRMED`
4. 记录入口：
   - `GET /api/v1/admin/reconciliation/activities/{activityId}`

## 阶段6：限流与缓存

1. 参与接口限流：
   - 活动级：`flashguard.ratelimit.activity.limit`
   - 用户级：`flashguard.ratelimit.user.limit`
2. 验收指标：
   - `flashguard_rate_limit_reject_total{scope="activity"}`
   - `flashguard_rate_limit_reject_total{scope="user"}`
3. 活动缓存：
   - `local-ttl-ms` 与 `redis-ttl-ms` 同步生效
4. 验证：活动详情读多次时，短时间内命中率上升且响应更稳定。

## 阶段7：分桶实验

1. 先跑单 key 指标，不立即分桶：
   - Redis CPU、ops/s、P99
2. 仅当单 key 变成明显瓶颈时再实施分桶（需集群场景）。
3. 证据模板见：`docs/validation/stage7-bucket-evidence.md`

## 阶段8：故障注入

1. 保持现有 6 个脚本不变。
2. 增补对账视角与重试观察：
   - 发生故障后执行对账接口，确认差异项是否逐步收敛。
3. 观察指标：
   - `flashguard_mq_backlog`
   - `flashguard_mq_consumer_latency`
   - `flashguard_reservation_compensation_failed_total`
   - `flashguard_rate_limit_reject_total`

