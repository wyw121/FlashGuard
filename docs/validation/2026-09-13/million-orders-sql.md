# 百万历史订单 SQL 实验：2026-09-13

**结论：PASS，范围仅限本次历史 SQL 数据集与查询实验。** 实际生成恰好 1,000,000 行；六组查询均保留 5 次预热及 20 次正式测量，索引前后、OFFSET/keyset 返回窗口逐字段一致。没有修改生产索引，也没有执行或证明百万 HTTP 请求、业务吞吐或生产容量。

本次运行目录为 [`20260913T125337Z-cc35a954`](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/summary.json)，UTC 12:53:37–12:57:02（北京时间 20:53:37–20:57:02）。当次 JUnit 结果为 `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 205.4 s`。完整[原始汇总](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/summary.json)保留了全部测量值，不只展示最快的一次。

## 数据集与环境

[行数验证](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/dataset-counts.json)实测：

| 项目 | 实际值 |
| --- | ---: |
| 历史形状订单 | 1,000,000 |
| 最小 / 最大 ID | 1 / 1,000,000 |
| 活动数 | 100 |
| 用户数 | 10,000 |
| CONFIRMED / CANCELLED | 900,000 / 100,000 |
| 造数批次 | 10 × 100,000 |

[`historical_orders`](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/historical-orders-schema.sql) 是专属新库中的实验表，保留订单字段和订单号/requestId/reservationId、活动用户组合的业务唯一约束；没有复制生产活动外键或同时运行订单写入业务，因此不是生产 `orders` 的完整运行环境。使用固定数字交叉连接与批次偏移造数，不使用随机业务数据；创建时间每十行同一秒，分页排序必须同时使用时间和 ID。[十批原始耗时](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/seed-batches.json)均断言插入 100,000 行。

[环境记录](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/environment.json)：MySQL 8.4.8，Connector/J 9.7.0，宿主 Windows 11/Oracle Java 21.0.9，JVM 可见 12 个处理器。InnoDB buffer pool 为 268,435,456 字节（256 MiB），`innodb_flush_log_at_trx_commit=1`，事务隔离为 REPEATABLE-READ。Docker HostConfig 的 Memory、NanoCpus、CpuQuota 均为 0，表示未配置独立容器内存/CPU 配额，**不能把 256 MiB buffer pool 当作整个容器内存限制**。

## 正式计时结果

单位均为 **毫秒**。每行正式样本数均为 **20**，此前 5 次预热排除在统计之外。中位数为排序后第 10、11 值均值；P95 使用 nearest-rank 第 19 值。报告编写时重新从原始数组计算，六组样本数量、中位数与 P95 均与汇总一致。

| 查询组及原始数据 | 样本 | 中位数 | P95 | 最小 | 最大 |
| --- | ---: | ---: | ---: | ---: | ---: |
| [user-before](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/user-before/timings.json) | 20 | 694.24410 | 819.95720 | 579.15080 | 877.78350 |
| [user-after](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/user-after/timings.json) | 20 | 6.51615 | 12.42440 | 3.80430 | 12.61720 |
| [activity-before](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/activity-before/timings.json) | 20 | 57.55115 | 78.79650 | 33.90850 | 78.80240 |
| [activity-after](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/activity-after/timings.json) | 20 | 7.14635 | 14.33160 | 5.17160 | 17.20800 |
| [deep-offset](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/deep-offset/timings.json) | 20 | 761.96105 | 857.10470 | 580.28850 | 894.56600 |
| [keyset](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/keyset/timings.json) | 20 | 7.33875 | 8.68150 | 5.11360 | 9.18540 |

本次中位耗时的“基线 / 候选”比值分别为用户查询 106.54、活动查询 8.05、深分页 103.83；P95 比值分别为 66.00、5.50、98.73。这些是该数据分布与本机环境下的实际观察，不是跨环境保证；测试也没有把“优化必须更快”写成通过条件。

计时包括 JDBC 执行、传输和完整 50 行结果物化，排除结果等价断言以及 EXPLAIN 的分析开销。下面执行计划中的 `actual time` 属于另一轮服务器执行，不可直接当作上表 JDBC 中位数。

## 实际执行计划

索引前后用户、活动查询各自的 SQL、过滤条件、参数、投影和返回窗口相同。基线保留业务唯一索引；增加的是实验候选联合覆盖索引，没有强制这两类查询选择候选索引。

| 比较 | 实际计划与读取规模 |
| --- | --- |
| [用户索引前](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/user-before/explain-analyze.txt) | 全表扫描实际 1,000,000 行，过滤后 100 行，再按 created_at/id 排序取 50 行 |
| [用户索引后](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/user-after/explain-analyze.txt) | 选择 `ix_history_user_status_created(user_id,status,created_at,id,activity_id)`，反向覆盖索引查找，实际输出 50 行，无额外排序节点 |
| [活动索引前](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/activity-before/explain-analyze.txt) | 使用既有 `uk_history_activity_user` 读取该活动 10,000 行，状态过滤后 9,000 行，再排序取 50 行 |
| [活动索引后](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/activity-after/explain-analyze.txt) | 选择 `ix_history_activity_status_created(activity_id,status,created_at,id,user_id)`，反向覆盖索引查找，实际输出 50 行，无额外排序节点 |
| [OFFSET](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/deep-offset/explain-analyze.txt) | 同一覆盖索引 `ix_history_status_created` 反向读取实际 800,050 行，跳过 800,000 行，输出 50 行 |
| [keyset](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/keyset/explain-analyze.txt) | 同一索引按游标条件作反向范围扫描，实际读取并输出 50 行 |

计划中的 `cost/rows` 估计数不总等于真实数据量，例如用户基线估计 991,312 行而实际扫描 1,000,000 行。本报告使用 `actual rows` 描述实际执行，不把估计数写成造数结果。[索引前](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/indexes-before.json)与[索引后](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/indexes-after.json)的完整定义也已保留。

## 游标成本与等价窗口

两种分页均过滤 `status='CONFIRMED'`，按 `created_at DESC,id DESC` 排序，并显式使用同一 `ix_history_status_created` 索引。OFFSET 800,000 与 keyset 的[实际返回窗口](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/keyset/result-window.json)逐字段相同，共 50 行；共同结果 SHA-256 为 `1d489583ffeaee0ab3fb7ce9190bf8afa2aa762a588fe9a9019c725bf738cfb1`。

[游标取得记录](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/cursor-acquisition.json)使用 OFFSET 799,999 读取上一行，实际游标为 `created_at=2020-01-01 03:05:11, id=111113`，耗时 **976.8853 ms**。这次寻找游标的成本单独保留，**未计入 keyset 单页耗时**。因此上表说明的是已持有游标时的下一页读取收益；不能据此声称随机跳到第 800,001 行只需约 7 ms，首次寻找游标也不能算作免费。

## 复现、哈希与缓存边界

完整数据集 SHA-256：`f7aa6fa9800c8d16d1930f459fd7f2a0bd15a3b5b7c7abc4c696b4f96562b3fb`。它按[哈希查询](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/dataset-hash-query.sql)中的九列、ID 顺序，以 UTF-8 字段和 TAB/LF 分隔计算。运行时逐行流式读取并再次断言恰好百万行。

保留的[种子 SQL](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/historical-orders-seed-batch.sql) SHA-256 为 `acef3eaf016699e3a6fcca96d22132a59da0b05b332abcc90ec8c9eab8ebc7a2`；[当次测试源码](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/HistoricalOrdersSqlBenchmarkTest.java) SHA-256 为 `5636c56f05bba550a45b46b1ac3400cc805ad1e2e326a1fe9bd5eef192a65489`。报告编写时复核了这两个保留文件的哈希；其余输入文件、pom 与镜像 ID 见[环境清单](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/environment.json)。Git HEAD 为 `ece8209ec152395a9a092a214b7eafab443288ed`，当时工作区有未提交修改，因此不能只用这个 HEAD 代表实际运行源码；应一并使用保留源码、输入哈希及[工作树补丁](../../../benchmark/datasets/results/20260913T125337Z-cc35a954/working-tree.patch)。

所有查询都在完整读取校验窗口及 5 次预热后计时；没有清理 OS page cache，没有冷启动查询测量，也没有证明整个表和索引始终驻留内存。索引创建本身也会改变缓存状态。六组固定按 user-before、activity-before、user-after、activity-after、deep-offset、keyset 顺序执行，没有随机交叉排序或独占宿主资源，不能把一次顺序实验推广为生产稳定增益。

重新执行需要 Docker、Java 17+、Maven；始终使用新建专属 MySQL 容器，不使用外部数据库：

```powershell
Set-Location 'D:\code\flashguard-workbench\flashguard'
mvn '-Dtest=HistoricalOrdersSqlBenchmarkTest' '-Dflashguard.sql-benchmark.enabled=true' test
```

[生成器与完整协议](../../../benchmark/datasets/README.md)说明重建顺序、统计口径和产物。后续是否修改生产索引，需要结合真实查询分布、写入成本、索引体积与独立重复实验决定；本次没有作出生产迁移。
