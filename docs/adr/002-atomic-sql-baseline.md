# ADR 002: 使用条件 UPDATE 作为库存正确性基线

- 状态：Accepted
- 日期：2026-09-04

## 背景

秒杀库存的首要不变量是不能扣成负数。应用层先查库存再更新会产生检查与写入之间的竞态；后续引入 Redis 或消息队列也不能取代数据库最终约束。

## 备选方案

1. 先 `SELECT` 再 `UPDATE`：实现直观，但并发下会超卖，除非再引入锁。
2. `SELECT ... FOR UPDATE`：正确，但持锁范围更大，热点活动下吞吐和尾延迟较差。
3. 乐观版本号重试：能检测冲突，但热点行会产生大量失败与重试，业务拒绝路径更复杂。
4. 单条条件 `UPDATE`：数据库原子检查 `available_stock > 0` 并扣减，以受影响行数表达成功或售罄。

## 决策

阶段 1 使用单条条件更新：

```sql
UPDATE activity_stock
SET available_stock = available_stock - 1,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE activity_id = ?
  AND available_stock > 0;
```

订单插入与扣库存处于同一事务。`request_id` 和 `(activity_id, user_id)` 的唯一约束是幂等与一人一单的最终并发边界；重复键时整个事务回滚，门面层在事务外重新读取事实并映射为 `REPLAY` 或 `DUPLICATE_PURCHASE`。

## 结果

库存行只在成功路径短暂加写锁，不需要应用重试。受影响行数为 0 在活动已确认存在的前提下稳定映射为 `SOLD_OUT`。未来 Redis 预占和 MQ 异步化必须保留这些数据库不变量与补偿验证。
