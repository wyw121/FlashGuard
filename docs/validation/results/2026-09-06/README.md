# 原始验收报告

验收源码：`ac6b6c7dbdee8e8ff72b06d2157c3bdc2e3aac3d`。

本目录的 `com.flashguard.*.txt` 是最终 51 项测试运行后，直接从 `target/surefire-reports` 复制的原始文本报告，共 10 个测试类，0 failures/errors/skipped。时间字段是测试运行耗时，不作为业务请求延迟指标。

`redis-compose-smoke.txt` 保留 Compose 健康检查后的 Redis 版本和持久化/淘汰配置输出。`mysql-http-baseline.txt` 是基线脚本实际输出的验收行。

镜像、完整命令、JAR 文件锁的处理以及功能边界见 [完整验证记录](../../2026-09-06-stage-2.md)。
