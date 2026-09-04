# Upstream Baseline Reading Map

## Purpose

This audit records what the supplied projects demonstrate and where their boundaries are. It is not a claim that FlashGuard implements those behaviors. FlashGuard starts with a smaller MySQL baseline and adds each reliability mechanism only after its own tests and measurements justify it.

## Goinggoinggoing/seckill

The current supplied snapshot wires `SeckillController` to `SeckillService`, with Spring selecting `SeckillServiceImplv3`. Activity validation happens in the controller; stock, order, Redis, and MQ work are delegated from service implementations.

### V1: MySQL baseline

Observed call path:

```text
controller/SeckillController.seckill
  -> service/impl/SeckillServiceImpl.seckill
     -> service/impl/GoodsServiceImpl.reduceStockWhenLeft
        -> dao/GoodsDao.reduceStockWhenLeft
           -> resources/mapper/GoodsDao.xml conditional UPDATE
     -> service/impl/OrderServiceImpl.createOrder
        -> dao/OrderDao.insertOrder
           -> resources/mapper/OrderDao.xml INSERT
```

The service transaction surrounds database stock deduction and order creation. The source also retains intentionally weaker select-then-update, pessimistic-lock, and optimistic-version variants for comparison. FlashGuard borrows only the experiment shape: start with an explicit conditional update and prove its invariant using a real MySQL concurrency test.

### V2: Redis plus an in-process queue

`SeckillServiceImplv2` moves admission to Redis with a decrement, records a pending marker, and submits database order creation to a fixed thread pool. This lowers synchronous database pressure, but a process crash can lose queued work and pending state is not a durable transaction fact. FlashGuard will use this as evidence for why an executor is not a reliable MQ substitute, not as an implementation template.

### V3: Redis plus RocketMQ

`SeckillServiceImplv3` executes a small Redis script against available and reserved counters, then calls `MQProducer.sendStockReductionTransactionMessage`. `SeckillTransactionListener` handles the producer local transaction/check path, while `StockReductionConsumer` processes the downstream message. `OrderCancellationConsumer` and the producer's delayed-message API cover a timeout-cancellation path.

The reading exposes the key boundary that FlashGuard must make stricter: a Redis reservation, producer local transaction, consumer database transaction, and compensation are separate idempotent state transitions, not one cross-resource ACID transaction.

### V4: protection and reconciliation

`RateLimitAspect` delegates to `RedisRateLimiter`; `SeckillServiceImplv3` maintains a local sold-out cache; `StockReconciliationService` compares Redis and database counters. These features are later-stage candidates. FlashGuard will not import the reference performance numbers or assume that a counter comparison alone is a safe repair rule.

## Yukinoshita52/seckill-system

Observed high-level path:

```text
seckill-engine/controller/SeckillController
  -> service/impl/SeckillServiceImpl
     -> service/impl/StockServiceImpl
        -> resources/lua/stock_deduct.lua
     -> mq/producer/SeckillOrderProducer
        -> mq/consumer/SeckillOrderConsumer
           -> database order/stock services
```

The project separates gateway, engine, admin, and framework modules and includes compensation Lua, an order DLQ consumer, activity cache, and stock services. It is useful for Redis atomicity and failure-path questions, but its module topology is deliberately not copied: FlashGuard remains a modular monolith until measurements expose a deployment boundary that needs independent scaling.

Because the upstream has no verified license, this source is idea-only. FlashGuard Lua and Java code must be written from its own contract tests.

## wayn111/waynboot-mall

Relevant reading path:

```text
domain-trade OrderSubmitSupport / submit-chain steps
  -> domain-inventory RedisStockPreDeductSupport
  -> domain-inventory OrderStockSupport and inventory flows
  -> message producer/consumer modules
  -> InventoryReconciliationService
```

The useful ideas are the separation of pre-admission from final database facts, an explicit release handle for a reservation, conditional repair that does not overwrite concurrent changes, and inventory flows as an auditable ledger. The project uses RabbitMQ; FlashGuard will later translate only the reliability pattern to RocketMQ's actual transaction/retry contracts.

## FlashGuard decisions derived from the audit

1. Start with one Spring Boot process and one MySQL transaction so the initial correctness boundary is observable.
2. Keep conditional stock deduction and a database uniqueness constraint even after Redis admission is added.
3. Treat request replay and one-person-one-order as different invariants.
4. Add a durable reservation before relying on RocketMQ transaction checks.
5. Make consumer and compensation transitions idempotent because retries are normal.
6. Do not add stock buckets, microservices, or circuit breakers without a measured bottleneck or a real synchronous remote dependency.

## Reference run status

The supplied reference snapshots were audited statically. They were not started during this audit because Docker Desktop's client is installed but the Docker engine is not running, and the main reference requires MySQL, Redis, and RocketMQ for the V3/V4 path. This limitation is recorded rather than presenting a static read as a successful runtime check.

