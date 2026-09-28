# 阶段 1–8 验收台账

本报告持续更新。代码存在、组件测试通过、故障验收通过与正式压测通过分别记录。

2026-09-28 补充：[混合用户流量与运行脚本验收](../2026-09-28-completion.md)。mysql-mixed 五轮 PASS；async-mixed 首次被宿主 Modern Standby 中断而 FAIL，接电重跑五轮 PASS。全量 verify：148 项、0 失败/错误、2 项长实验 opt-in 跳过，146 项实际执行通过。保留失败原始数据，新增结果仍只覆盖 10 requests/s 配置。

| 范围 | 已获得证据 | 剩余门槛 |
| --- | --- | --- |
| 1 MySQL 基线 / 2 Redis 预占 | 2026-09-17 全量 `mvn verify`：148 tests、0 failures/errors；[正式 HTTP 五轮结果](../../../benchmark/results/fac1c6586b94f107f9c104c16694eae04b29f48c/2026-09-17T12-35-26.631044Z-98e87c79/aggregate.json) 中 MySQL 三场景均 PASS | 仅覆盖配置的 10 requests/s，不是最大容量结论 |
| 3 事务消息 / 4 消费幂等 / 5 补偿对账 | 真实 MQ 11 项通过；全量回归含 producer-before-local 与 producer-after-commit 无同组存活成员冷恢复；正式 HTTP async 三场景均 PASS | 不覆盖数据库提交中的强杀；仅覆盖配置的 10 requests/s |
| 6 缓存与限流 | Caffeine/共享 Redis/负缓存及限流组件测试；正式 HTTP 本地/全局限流各五轮 PASS，零系统错误、零 dropped iterations、最终 Broker lag 为零 | 仍需独立的持续过载/容量上探实验 |
| 7 库存分桶 | [三主节点五轮 A/B](stage7-cluster-ab.md)，50,000 次成功预占和不变量通过 | 当前证据不支持稳定容量收益，生产保留单热点 |
| 8 可观测性与故障注入 | 六个隔离脚本与真实 MQ 11 项验收；正式 HTTP 五轮资源采样已归档；Broker 回查恢复消息 `traceId` 且 MDC 在回查后恢复的 14 项回归已通过 | 容量上探与生产隔离环境验证 |

## 已完成的真实 MQ 试验

原始结果在 [real-mq](real-mq/results.json)，故障观测在 [fault-observations.txt](real-mq/fault-observations.txt)。MySQL 暂停期间观察到初次失败及 Broker 重投失败，恢复后重投成功；Redis 暂停后的延迟预占恢复到库存 200；停止消费者造成的 20 条消息积压最终降为 0。

Broker 使用官方 RocketMQ 5.3.2 Maven 制品，运行于隔离 Linux 容器。官方 Docker 镜像拉取在当前网络反复 unexpected EOF；本地 Windows 磁盘约 92% 使用率会触发官方 Broker 最高 90% 的保护。因此使用有充足空间的 Docker Linux 存储，未关闭或绕过 Broker 磁盘保护。运行镜像由固定 Node 基础镜像和固定 Debian OpenJDK 17.0.20.1 软件包构建；宿主应用为 Java 21.0.9。测试运行镜像及启动脚本见 config/rocketmq/Dockerfile.test-runtime、RocketMqProcessSupport。

消费者强杀覆盖真实 Broker 收件后、业务 JDBC 事务调用前的死亡，未声称覆盖数据库提交进行中的强杀。所有故障均只操作本次测试创建的容器和 JVM。

## HTTP 压测验收口径

正式运行 `2026-09-17T12-35-26.631044Z-98e87c79` 已完成 8 个场景的 60 秒预热（排除统计）和 180 秒稳态 × 5 轮；[aggregate](../../../benchmark/results/fac1c6586b94f107f9c104c16694eae04b29f48c/2026-09-17T12-35-26.631044Z-98e87c79/aggregate.json) 为 `overallStatus=PASS`、`suiteCompleted=true`。每轮 k6 exit 0、系统错误和 dropped iterations 均为零，数据库、Redis、durable reservation 与 MQ 不变量均通过。MySQL-success 的五轮 committed-order RPS 中位数为 9.915，async-success 为 9.801；它们仅适用于此 10 requests/s 配置，不构成最大 QPS 或生产容量主张。

早期 async 10/s 死锁失败、MySQL 50/s 冷启动超时及丢弃迭代记录仍保留，不被此次成功校准改写。Rate 指标以原始 k6 `metrics.system_error_rate.value` 为准，原先派生文件中的错误零值已识别为解析缺陷，不能作为成功证据。

完整协议为每场景 60 秒预热（排除统计）、180 秒稳态、5 轮。保留全部 k6 raw JSON、汇总、资源采样、环境与源码指纹以及 DB/Redis/MQ 不变量。短时预检一律 REVIEW；系统错误、丢弃迭代或不变量失败判 FAIL。Broker 额外消息无法按当前业务轮归因时不宣称其为精确重复率，性能结论保留 REVIEW。

源码与历史验证已分别保存于本地提交 `fac1c65`、`9ef969b`。早期实验运行时确有未提交修改，应继续使用各自归档的 dirty diff 与源码哈希识别当时构建；不要把这一历史边界误读为当前仍未提交。2026-09-17 正式结果目录按 `fac1c6586b94f107f9c104c16694eae04b29f48c` 归档。本地提交不等于已推送 GitHub。
