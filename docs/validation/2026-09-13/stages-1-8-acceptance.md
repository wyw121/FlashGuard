# 阶段 1–8 验收台账

本报告持续更新。代码存在、组件测试通过、故障验收通过与正式压测通过分别记录。

| 范围 | 已获得证据 | 剩余门槛 |
| --- | --- | --- |
| 1 MySQL 基线 / 2 Redis 预占 | 历史并发与幂等集成验证；当前 MySQL HTTP 预检成功 | 最终全量回归与五轮基线 |
| 3 事务消息 / 4 消费幂等 / 5 补偿对账 | [真实 MQ 11 项全部通过](real-mq/results.json)；本地事务死锁修复后 20/20 并发提交通过；终态响应修复后 26 项单元测试及五个 async HTTP 校准场景技术通过 | 最终全量回归、正式五轮 HTTP；Producer 无存活同组成员时冷重启恢复尚无专项证据 |
| 6 缓存与限流 | Caffeine/共享 Redis/负缓存及限流组件测试；最新本地/全局 HTTP 限流校准分别 53/51 次正确拒绝，无系统错误、丢弃迭代或最终 Broker 积压；[已有逐调用开销记录归档](stage6-overhead/README.md) | 正式持续过载验证；早期开销记录缺同期环境/源码来源，完整可复现开销对照仍需带清单重测 |
| 7 库存分桶 | [三主节点五轮 A/B](stage7-cluster-ab.md)，50,000 次成功预占和不变量通过 | 当前证据不支持稳定容量收益，生产保留单热点 |
| 8 可观测性与故障注入 | 六个隔离脚本已落地，11 项真实 MQ 验收含全部六类故障；最新五个 async 校准场景资源采样完整 | 最终全量验证、正式压测资源采样归档；Broker 回查线程的原 traceId 透传尚需补齐验证 |

## 已完成的真实 MQ 试验

原始结果在 [real-mq](real-mq/results.json)，故障观测在 [fault-observations.txt](real-mq/fault-observations.txt)。MySQL 暂停期间观察到初次失败及 Broker 重投失败，恢复后重投成功；Redis 暂停后的延迟预占恢复到库存 200；停止消费者造成的 20 条消息积压最终降为 0。

Broker 使用官方 RocketMQ 5.3.2 Maven 制品，运行于隔离 Linux 容器。官方 Docker 镜像拉取在当前网络反复 unexpected EOF；本地 Windows 磁盘约 92% 使用率会触发官方 Broker 最高 90% 的保护。因此使用有充足空间的 Docker Linux 存储，未关闭或绕过 Broker 磁盘保护。运行镜像由固定 Node 基础镜像和固定 Debian OpenJDK 17.0.20.1 软件包构建；宿主应用为 Java 21.0.9。测试运行镜像及启动脚本见 config/rocketmq/Dockerfile.test-runtime、RocketMqProcessSupport。

消费者强杀覆盖真实 Broker 收件后、业务 JDBC 事务调用前的死亡，未声称覆盖数据库提交进行中的强杀。所有故障均只操作本次测试创建的容器和 JVM。

## HTTP 压测验收口径

最新[校准记录](http-calibration.md)为 `2026-09-13T13-31-11.516830900Z-29d1ecf6`：5 秒预热、10 秒测量、每场景 1 轮、10 requests/s、100 VUs。async-success、async-soldout、async-replay、async-limiter-local、async-limiter-global 五个场景全部技术 PASS，正式 benchmark 状态仍为 REVIEW。对应日志 `work-notes/http-calibration-terminal-fix.log` 为 27 tests、0 failures/errors/skips、BUILD SUCCESS，其中 26 项为 ParticipationService 单元测试，1 项 HTTP 测试包含五场景。此结论不覆盖最终 `mvn verify`。

早期 async 10/s 死锁失败、MySQL 50/s 冷启动超时及丢弃迭代记录仍保留，不被此次成功校准改写。Rate 指标以原始 k6 `metrics.system_error_rate.value` 为准，原先派生文件中的错误零值已识别为解析缺陷，不能作为成功证据。

完整协议为每场景 60 秒预热（排除统计）、180 秒稳态、5 轮。保留全部 k6 raw JSON、汇总、资源采样、环境与源码指纹以及 DB/Redis/MQ 不变量。短时预检一律 REVIEW；系统错误、丢弃迭代或不变量失败判 FAIL。Broker 额外消息无法按当前业务轮归因时不宣称其为精确重复率，性能结论保留 REVIEW。

当前工作树包含历史未提交代码，因此仅 Git HEAD 不足以标识构建；各实验同时保存 dirty diff 与源码哈希。没有创建提交或推送远端，不把本地数据描述成已在 GitHub 发布。
