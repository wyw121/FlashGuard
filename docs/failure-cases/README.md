# 阶段8故障注入案例

执行前请先保持应用与依赖服务可达，并提前准备活动 `1001`（或自定义活动）。

```powershell
pwsh -NoProfile -File .\scripts\failure-cases\inject-redis-unavailable.ps1
```

每个案例包含：

- 现象
- 预期
- 实际（运行脚本后记录）
- 恢复
- 是否丢单 / 超卖

脚本对应关系见下：

- `inject-redis-unavailable.ps1`：Redis 不可用
- `inject-mysql-slow-or-down.ps1`：MySQL 慢响应/断开
- `inject-producer-kill.ps1`：Producer 进程杀掉
- `inject-consumer-kill.ps1`：Consumer 进程杀掉
- `inject-consumer-forced-exception.ps1`：消费者强制异常
- `inject-message-backlog.ps1`：消息堆积

### 管控端点（Stage 8）

- `GET /actuator/metrics/flashguard_mq_backlog`
- `GET /actuator/metrics/flashguard_mq_consumer_latency`
- `POST /api/v1/admin/faults/consumer/forced-exception?enabled=true|false`
- `GET /api/v1/admin/faults/consumer/forced-exception`
- `POST /api/v1/admin/faults/consumer/replay?requestId=<requestId>&messageKey=<messageKey>`
- `GET /api/v1/admin/faults/reservations/{requestId}`
