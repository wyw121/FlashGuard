# 阶段 3–8 连续推进

依据：工作区根目录 FlashGuard_实施方案书.md。保留已验证的本地补偿实现与用户未提交改动。

1. 接入 RocketMQ 5.3.2 Remoting 事务生产者：先 half message，再在本地事务写 durable reservation；回查查 DB，未知返回 UNKNOWN，缺失超时后通过持久化取消权阻止迟到写入。入口使用显式 mysql/async 模式，async 故障不隐式切同步。
2. 接入 Push Consumer，幂等消费、关联 ID、消费失败重试及真实 DLQ。真实 Broker、MySQL、Redis 验证提交/回滚/回查/100 次重复/暂态恢复/持续失败 DLQ。
3. 补齐阶段 6 Caffeine + Redis 缓存与本地/可选全局 Lua 限流；真实 Redis 及并发测试，记录开销对比。
4. 对现有六类故障脚本改为真实链路可重复验证；记录状态收敛、库存不变量及真实 MQ backlog 指标。
5. 阶段 7 记录单 key 基线及是否需要 Cluster 的证据；没有真实 Cluster A/B 不声明分桶收益。
6. 统一全量测试、交付记录和只读审查。每阶段结论明确 PASS/REVIEW/FAIL。
7. 按方案书 9.5 执行真实 HTTP k6：预热 60 秒、稳态 180 秒、同配置五轮，保留失败/预检轮与所有原始结果；分别报告入口、最终确认延迟、实际订单吞吐、拒绝、系统错误、丢弃迭代和一致性。增加同一三主节点 Cluster 的单热点/分桶 A/B。

Ruling: 使用当前 codex/stage-2-redis-reservation 分支继续；工作树含用户既有未提交实现，不自动清理或提交这些改动。
Ruling: 固定 RocketMQ 官方 Docker 快速启动示例的 5.3.2 版本；使用 TransactionMQProducer/DefaultMQPushConsumer 以兼容现有 Spring Boot 3.5，不引入未知 starter 自动配置。
Ruling: 阶段 6 独立子任务仅修改缓存/限流相关文件；pom.xml、application.yml、参与服务、测试启动与 Maven 执行由主任务统一协调。
