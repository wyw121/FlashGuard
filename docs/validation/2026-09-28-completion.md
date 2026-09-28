# 2026-09-28 方案执行与验收记录

本轮从干净提交 `9ef969b4cfb4d5e6a153eb1be5d68f2d432f1634` 继续，在原有 `codex/stage-2-redis-reservation` 分支工作。先核对方案书与归档结果，再补齐混合流量和可复现运行脚本。没有修改业务运行源码。

## 进度核对

| 方案范围 | 已核实证据与边界 |
| --- | --- |
| 阶段 1–2 | 同步 MySQL / Redis Lua 基础链路已有实现和真实组件测试 |
| 阶段 3–5 | 真实 half message、本地 durable 事务、回查、消费幂等、retry/DLQ、超时补偿、孤儿恢复和对账；Producer 冷恢复与 Consumer 业务事务前退出已有测试 |
| 阶段 6 | Caffeine / Redis、活动与用户限流；原有本地 / 全局限流五轮结果 PASS |
| 阶段 7 | 三主节点五轮 A/B 已执行，未建立稳定收益，按方案保留单热点生产实现 |
| 阶段 8 | HTTP/JVM/DB/Redis/MQ 指标、关联日志和六类真实故障脚本已有实现；故障窗口边界见对应文档 |
| 原有 HTTP | 2026-09-17 八场景、40 个正式轮次的原始 summary 已逐轮复核：系统错误与 dropped iterations 均为零，原汇总 PASS |
| 百万历史订单 | SQL 数据集、索引前后计划与同窗口分页对照已有独立报告；不代表百万 HTTP 请求 |

历史证据入口：[阶段台账](2026-09-13/stages-1-8-acceptance.md)、[正式八场景原始汇总](../../benchmark/results/fac1c6586b94f107f9c104c16694eae04b29f48c/2026-09-17T12-35-26.631044Z-98e87c79/aggregate.json)、[百万订单 SQL](2026-09-13/million-orders-sql.md)、[Cluster A/B](2026-09-13/stage7-cluster-ab.md)。

## 本轮补充

方案 9.4 的混合用户流量此前缺少独立场景。本轮新增 `mysql-mixed` / `async-mixed`：每两个请求共享用户、requestId 不同；库存足够，不使用售罄快速失败代替混合业务。N 次请求必须对应 `ceil(N/2)` 张订单和 `floor(N/2)` 次重复购买拒绝，售罄、限流或系统异常均判为错误。

Node 契约测试先复现三项失败（缺少用户配对、奇数窗口、错误接受 SOLD_OUT），修复后四项通过，包含原有 replay 回归。真实短预检中两条路径各发出 101 次请求，均为 51 单、50 次预期拒绝、technical PASS；因仅 10 秒、一轮、无预热，正式判定仍为 REVIEW。[预检原始数据](../../benchmark/results/9ef969b4cfb4d5e6a153eb1be5d68f2d432f1634/2026-09-28T10-39-50.171541500Z-cb6d8cd3/aggregate.json)保留其工作树指纹。

三个 PowerShell 包装器支持独立克隆，Maven settings 不再必须位于工作台外部目录；已有配置自动沿用，也可用 `-MavenSettings` 指定。Maven 在项目根执行，结束或异常后恢复调用目录。脚本测试使用隔离临时目录与命令替身验证调用契约，不将其冒充 Maven/Docker 集成测试。独立审查发现测试错误假定调用方位于仓库根，已修正并从父目录验证。

README 与历史台账的过时进度、RocketMQ SDK 版本说明、Producer 冷恢复边界已更新；新增 [ADR 索引](../adr/README.md)和[代码链路讲述](../interview-notes/project-walkthrough.md)。

## 本轮正式验证

正式 mixed 运行：`82b0718d0865a3a8f9ce6a243c9603b0472d40f9`，源码指纹 `1528e88f01179e44158c08f9a69ae24a4d980598af4bc3ecad155efded899826`。启动记录为干净工作树。每条路径 60 秒预热，180 秒稳态 × 5，目标 10 requests/s，100 VUs，关闭定时补偿/对账，与原有协议保持一致。

运行结论：**本次整组 FAIL；同步 mixed 五轮 PASS，异步 mixed 第一轮受宿主机待机干扰，后续轮次未执行。** [原始汇总](../../benchmark/results/82b0718d0865a3a8f9ce6a243c9603b0472d40f9/2026-09-28T10-44-16.868955800Z-03e19b72/aggregate.json)保留该结论，不能把无超卖改写成性能通过。

同步五轮分别为 1801/1801/1800/1801/1800 次请求，901/901/900/901/900 单，每轮 900 次预期重复购买拒绝。所有轮次系统错误和 dropped iterations 为零；实际订单 RPS 中位数约 4.98，确认延迟 P50/P95/P99 的轮间中位数为 65/75/92.04 ms。该吞吐对应约一半拒绝的混合流量，不代表全成功容量。

异步预热 600 次请求、300 单、300 次拒绝，Broker lag=0。正式第一轮只发出 670 次请求（目标 1800），持久化 335 单、335 次业务拒绝，但客户端确认只有 334 次；dropped_iterations=1，k6 最终报告 100 interrupted iterations，系统错误率的分母仅为 669 个已记录结果。库存与唯一性不变量通过不改变响应/负载门槛失败。

[Windows 待机事件](2026-09-28/standby-events.json)记录 19:05:27 因 Idle Timeout 进入 Modern Standby；19:21:39 再次转换状态，20:12:51 键盘唤醒。应用日志同时记录 Hikari housekeeper 约 16 分 34 秒的时间跳跃，k6 的三分钟场景跨越约 17 分钟墙钟时间。证据支持该轮被宿主机暂停干扰，不能作为服务容量结论。[Maven 日志](2026-09-28/mixed-formal-maven.txt)与[JUnit 报告](2026-09-28/mixed-formal-junit.txt)一并归档。

后续恢复条件：接通电源、保持开盖，以进程作用域临时电源请求阻止空闲待机，结束后释放；不修改永久电源方案。Windows 在 Modern Standby 电池模式下会在睡眠超时后五分钟终止 system/execution 请求，见 [Microsoft PowerSetRequest](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-powersetrequest)。重跑需另建结果目录，保留本次 FAIL。

全量回归：本轮尚未执行，待电源条件恢复后串行执行。

## 结论边界

交付为本地后端 API、测试与文档，不包含前端或管理控制台。指定 10 requests/s 的通过不能写成最大容量；生产隔离环境、后台任务同时运行的总容量和百万 HTTP 请求仍未验收。数据库提交进行中的随机强杀也不在当前 Producer/Consumer 故障窗口之内。已有提交和结果存于本地，未据此声称已发布到 GitHub。
