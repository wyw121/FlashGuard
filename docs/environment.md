# FlashGuard 开发环境

记录时间：2026-09-05，Asia/Shanghai。

## 本机实际观测

| 项目 | 观测值 |
| --- | --- |
| 操作系统 | Windows 11，amd64（Java 运行时报告） |
| Maven 使用的 JDK | Oracle JDK 21.0.9 LTS |
| Maven | 3.9.10 |
| Docker Desktop | 4.45.0 (203075)，已从阶段 0 的不可用状态恢复 |
| Docker Client | 28.3.3，API 1.51 |
| Docker Engine | 28.3.3，Linux/amd64，`desktop-linux` context |
| Docker Compose | v2.39.2-desktop.1 |
| 本机 MySQL 回退 | MySQL 9.2.0，独立的 `flashguard_test` 测试库 |
| CPU / 内存 | 未记录；阶段 0 执行环境中的 WMI 访问被拒绝 |

`docker version` 已能同时返回 Client 和 Server 信息，说明 Docker Desktop/Engine 已恢复。项目使用 `maven.compiler.release=17`，因此用 JDK 21 运行 Maven 不会改变 Java 17 的字节码与 API 目标。

## 锁定的应用与测试版本

| 组件 | 版本 |
| --- | --- |
| Java 源码/字节码目标 | 17 |
| Spring Boot | 3.5.16 |
| MyBatis Spring Boot Starter | 3.0.5 |
| Testcontainers | 2.0.5 |
| 正式 MySQL 容器目标 | `mysql:8.4.8` |
| RocketMQ Spring Boot Starter（后续阶段） | 2.3.6 |

`compose.yml` 和 Testcontainers 都固定使用 `mysql:8.4.8`，不使用 `latest`。Flyway 迁移仍是数据库结构的唯一来源。

## 2026-09-05 验证快照

由于拉取 `mysql:8.4.8` 时镜像仓库连接返回 `unexpected EOF`，该镜像当前未缓存在本机；因此尚不能宣称 Compose 健康检查、Flyway 空库迁移或默认 Testcontainers 测试已经在正式目标镜像上通过。

为继续验证应用行为，本轮临时使用本机 MySQL 9.2.0 的独立 `flashguard_test` 库作为外部测试数据库，实际结果如下：

- `ActivityQueryServiceTest`：2/2 通过；
- `ParticipationMySqlIntegrationTest`：6/6 通过；除 1000 个请求以 32 个工作线程竞争 100 件库存并得到 100 张订单、库存 0、无超卖外，还覆盖两个相同 `requestId` 的并发请求只创建一单且只扣一次库存、同一用户使用不同 `requestId` 并发参加时只创建一单且失败事务回滚库存，以及请求标识的大小写敏感语义；
- 不依赖 Docker 的测试合计 24/24 通过：应用上下文 1/1、MVC 13/13、业务与标识单元测试 10/10。

MySQL 9.2.0 只是在目标镜像不可取得时使用的本机测试回退，不是正式运行版本，也不能替代 `mysql:8.4.8` 的最终 Compose/Testcontainers 验收。

测试支持通过 Maven 系统属性显式选择外部数据库；替换占位凭据后可使用：

```powershell
mvn `
  '-Dflashguard.test.db.url=jdbc:mysql://127.0.0.1:3306/flashguard_test' `
  '-Dflashguard.test.db.username=<test-user>' `
  '-Dflashguard.test.db.password=<test-password>' `
  clean verify
```

不提供 `flashguard.test.db.*` 属性时，测试会恢复默认行为，由 Testcontainers 启动 `mysql:8.4.8`。

## 复现与报告要求

- 正式验收需要镜像仓库恢复后成功取得 `mysql:8.4.8`，执行 `docker compose up -d --wait mysql` 和 `mvn clean verify`；
- 不得把 MySQL 9.2.0 回退结果写成 MySQL 8.4.8 验证结果；
- 性能或基准报告必须补充实际 Docker Engine、容器 digest、CPU、内存、JVM 参数和产生结果的 Git commit；
- 项目能够构建或参考项目 README 中存在性能数字，都不能单独证明本项目的性能。
