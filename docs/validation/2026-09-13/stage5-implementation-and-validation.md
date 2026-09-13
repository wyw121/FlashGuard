# 阶段 5 本地补偿与对账：2026-09-13

**本轮结果：PASS（本地工程验证）**。2026-09-13 16:08 核验 `mvn verify` 退出码 0：82 tests、0 failures、0 errors、0 skipped，JAR 已生成。运行时 Java 21.0.9，编译目标 Java 17；真实组件为 MySQL 8.4.8、Redis 7.4.8。方案整体仍为 REVIEW，未通过真实 MQ 和完整故障验收。

## 本轮交付

- 修复原有未提交实现的编译错误、无关联 ID 请求的 HTTP 过滤器空指针、测试构造参数与 Mockito 匹配器问题。
- 补偿 Lua 参数对齐、写前校验、释放墓碑、重复释放幂等；恢复 `CANCEL_PENDING/COMPENSATION_FAILED` 扫描，保留原消息键，单条失败不终止整批。
- Redis 成功但 DB 未写入时补建 durable 记录并恢复；原始 requestId 分页，损坏记录隔离与持久化审计。
- V4 增加 `reconciliation_issue` 和过期扫描索引；V5 允许取消后的同用户新请求，保留取消历史及有效预占唯一约束。
- 对账绕过缓存，核对 MySQL、Redis 库存和订单/预占计数；非数字库存及 Redis 读取失败也写问题表。增加定时对账，不自动覆盖库存。
- 消费者在同一事务内先插入订单，再扣库存，避免 DuplicateKey 分支多扣库存；库存不足时整个事务回滚。
- 活动详情缓存 Redis 读取失败回退数据库；现有单机缓存实现仍未替换为方案书指定的 Caffeine。

## 可重复验证

前置：Windows PowerShell、Java 17+、Maven、Docker Desktop Linux engine。测试运行在独立 MySQL 8.4.8、Redis 7.4.8 容器，自动迁移并准备数据，无需手工启动应用、预热活动或删除业务库。不要向这些破坏性测试传入生产或共享数据库的 `flashguard.test.db.*` 参数。

```powershell
# 阶段 5 针对性测试与打包
& 'D:\code\flashguard-workbench\flashguard\scripts\verify-stage5.ps1'

# 全项目测试与打包
& 'D:\code\flashguard-workbench\flashguard\scripts\verify-stage5.ps1' -Full
```

通过条件：Maven 退出码 0，所有选中用例 0 failures / 0 errors / 0 skipped，JAR 成功生成。`target/surefire-reports` 保存当前测试报告。本轮全量日志保存在 `D:\code\flashguard-workbench\work-notes\stage5-final-verify.log`；结果汇总见本目录 `stage5-test-results.json`。

## 关键断言

| 场景 | 断言 |
|---|---|
| 同一预占释放 100 次 | 库存只归还一次，旧 requestId 不能重新预占或释放新请求 |
| 释放时库存损坏 | 抛出明确状态错误，request/user/pending 均不被部分删除 |
| Redis-only 崩溃窗口 | 原无 DB 记录，恢复后有 durable CANCELLED 且库存恢复 |
| Redis 释放后 DB 更新前中断 | 重建扫描器后从持久化状态完成取消，库存不重复增加 |
| 消费与取消竞争 20 轮 | 每条最终 CONFIRMED 或 CANCELLED，DB/Redis 库存与订单数一致 |
| 同用户取消后新请求 | 历史取消行保留，新请求可以确认，最终仅一条订单 |
| 消息服务调用重复 100 次 | 同一订单号、订单只有 1 条、DB 库存只扣 1 次 |
| DB 库存扣减失败 | 新插入订单和预占状态变更一起回滚 |
| 损坏 Redis 库存 | 对账 balanced=false，问题表持久化差异 |
| 同页首个 orphan 记录损坏 | 坏记录进入审计，健康记录仍进入恢复 |
| 重复差异观察 | 一条审计记录，observations 增加 |

## 结论边界与下一阶段

本轮验收对象是后端代码、真实 MySQL/Redis 本地状态机和 HTTP 契约；没有新增前端。真实 RocketMQ 依赖、事务生产者、Broker 回查、retry/DLQ 尚未接入，不能把直接调用消费者、待处理 DB 行指标或预占 PENDING 当作真实 MQ 验收。重建扫描器模拟持久化中断点，尚未执行真实进程 kill 的完整故障链路。

下一步应回到阶段 3–4 的未完成项：接入真实 RocketMQ 并验证事务回查、重投和死信，再运行阶段 5 的 producer/consumer kill 收敛验收。阶段 6 的 Caffeine、全局限流开销对比，以及阶段 7 的单 key/Cluster 基准没有验收证据；不能据本轮测试宣称阶段 0–8 全部完成或容量通过。

消费者插入先于库存扣减，和同步基线的锁顺序不同，混合路径可能出现数据库死锁；事务会回滚，真实 MQ 接入时仍须验证暂态错误重试。Redis/MySQL 非原子跨资源快照可能产生瞬时对账差异，当前只记录问题，不自动修改库存。
