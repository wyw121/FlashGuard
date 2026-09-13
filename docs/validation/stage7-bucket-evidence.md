# 阶段7：分桶实验与证据化

## 结论

- 当前阶段默认不启用 Redis 分桶。
- 约束：单机单实例 Redis 为单线程服务，按活动拆 key 不能带来跨核收益；收益依赖于 Redis Cluster/多主分片。
- 当前建议：先运行单 key 指标采样，再评估是否需要分桶扩展。

## 实验记录模板

- 实验时间：
- 活动ID：
- 压测脚本：
- 总 VU：
- Redis `INFO commandstats`：
  - 当前 ops/s：
  - P99：
  - CPU：
- 结论：
  - `PASS`：单 key 足够
  - `NEED SPLIT`：确认为热点瓶颈后再分桶（要求集群）

