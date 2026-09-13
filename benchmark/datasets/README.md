# 百万历史订单 SQL 实验

这个实验在每次新建的 `mysql:8.4.8` 专属 Testcontainer 中生成 **恰好 1,000,000 条历史形状订单**，比较 SQL 查询与分页。测试不连接应用数据库，不读取 `flashguard.test.db.url` 等外部数据库覆盖配置，不修改生产表或 Flyway 索引；容器结束即销毁。

需要 Docker 正常运行、Java 17 或更高版本和 Maven。单独运行，避免同时压测同一台机器：

```powershell
Set-Location 'D:\code\flashguard-workbench\flashguard'
mvn '-Dtest=HistoricalOrdersSqlBenchmarkTest' '-Dflashguard.sql-benchmark.enabled=true' test
```

默认 `mvn test` 跳过该实验；只有上述开关为 `true` 才建库与造数。运行最长允许 30 分钟，实际时间以本机结果为准。不存在缩小为十万行仍声称完成百万实验的选项。

## 数据与重建

- `historical-orders-schema.sql` 建实验表 `historical_orders` 和 10 行 `seed_digits`。订单字段、订单号/requestId/reservationId 唯一约束以及 `(activity_id,user_id)` 唯一约束对应业务形状；实验中不创建活动表，因此省略活动外键。此表不是生产 `orders` 的精确执行环境。
- `historical-orders-seed-batch.sql` 使用五个数字表交叉连接，每次插入 100,000 行；测试将会话变量 `@batch` 依次设为 0..9，执行十批。每批独立自动提交，断言插入数，并保留耗时。
- ID 为 1..1,000,000；100 个活动 × 10,000 个用户，每活动每用户恰好一条；CONFIRMED 900,000 条、CANCELLED 100,000 条。创建时间从固定 `2020-01-01` 起，每 10 行同一秒，因此分页必须同时比较 `created_at,id`。
- 整个数据集按 ID 排序流式读取，九个字段按 UTF-8、TAB 分列、LF 分行计算 SHA-256。所有字段非空。生成 SQL 与测试源码同时记录 SHA-256，并复制到结果目录，能检查重建是否一致。

SQL 文件包含实验建表和索引操作，只用于独立新库；自动测试不需要手工执行 SQL。如果手工复现，请使用新的临时数据库，按 schema → 十次设置 `@batch` 并执行 seed-batch → 查询基线 → candidate-indexes → 相同查询 → pagination-index 的顺序执行。`seed_digits` 是普通实验表，因为 MySQL 临时表不能在一个查询中被五次别名重用。

## 比较协议

1. `query-user.sql` 和 `query-activity.sql` 分别按 userId/activityId 与 CONFIRMED 过滤，按创建时间和 ID 倒序取 50 条。先记录基线，再添加两个实验联合覆盖索引；索引前后的 SQL 文本、参数、投影和返回窗口完全相同。基线保留业务唯一索引，不是假设“完全无索引”。不强制优化器选择候选索引，真实选择以执行计划为准。
2. 两种深分页共用 `ix_history_status_created`，SQL 均显式 `FORCE INDEX`，按同一 CONFIRMED 集合排序。OFFSET 800,000 与携带上一页末尾游标的 keyset 返回相同 50 条，逐字段断言。游标由 OFFSET 799,999 单独取得，获取 SQL 与耗时保留在 `cursor-acquisition.json`；它不计入 keyset 单页耗时。随机跳转到深页仍要付出寻找游标的成本，keyset 的收益适用于已经持有游标的连续翻页。
3. 每个查询先记录 `EXPLAIN ANALYZE` 和 JSON 计划，完整取一次校验窗口，再执行 **5 次完整预热与 20 次计时**。保留每一次耗时，中位数取排序后第 10、11 值均值，P95 采用 nearest-rank 第 19 值。计时覆盖 JDBC 执行、网络传输及完整结果物化，不包含逐字段断言和 EXPLAIN 自身的分析开销。
4. 缓存口径是每查询预热后的运行，不清 OS page cache、不宣称冷缓存。固定按 user-before、activity-before、user-after、activity-after、deep-offset、keyset 顺序执行；运行在共享宿主机，记录实际 Docker 资源限制，不能视为独占服务器结论。InnoDB buffer pool 固定 256 MiB。

## 证据与判定

结果保存在 `benchmark/datasets/results/<UTC时间-随机ID>/`，失败也保存已产生的结果：

- `summary.json`：PASS/FAIL、实际数据量、数据集哈希、每查询的原始耗时/中位数/P95、前后比值、结果等价验证。
- 每查询目录：实际 `query.sql`、`explain-analyze.txt`、`explain-json.txt`、`result-window.json` 和 `timings.json`。
- `seed-batches.json`、`dataset-counts.json`、`dataset-hash-query.sql`、`executed-statements.sql`：造数批次、完整行数/分布验证和执行 SQL。
- `indexes-before.json`、`indexes-after.json`、`environment.json`、`working-tree.patch`：索引、MySQL/Connector/J/JVM/宿主版本、容器镜像 ID/HostConfig、MySQL 配置、Git SHA/脏状态、脚本与测试源码哈希。结果目录还复制了当时的 SQL 和测试源码。

PASS 表示恰好百万行、各组返回窗口相同、计划与 20 次原始测量完整保存；**不以“优化必须更快”硬编码通过门槛**。实际是否更快、快多少，应根据本次原始数据判断。只在独立实验表比较索引，尚未据此修改生产索引，也没有百万 HTTP 请求、并发吞吐或业务容量的结论。

官方资料：[MySQL 8.4 EXPLAIN/EXPLAIN ANALYZE](https://dev.mysql.com/doc/refman/8.4/en/explain.html)；[Connector/J 流式 ResultSet 的实现条件](https://github.com/mysql/mysql-connector-j/blob/release/9.x/src/main/user-impl/java/com/mysql/cj/jdbc/StatementImpl.java)。
