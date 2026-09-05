# ADR 001: Java 17 与 Spring Boot 3.5 基线

- 状态：Accepted
- 日期：2026-09-04

## 背景

FlashGuard 需要一条可长期维护、能在本机 JDK 21 上构建，又不把部署环境抬得过高的 Java 基线。方案同时要求使用现代 Spring Web、Validation、事务、Flyway 与 MyBatis 集成。

## 决策

源码和字节码目标固定为 Java 17，构建机允许使用 JDK 21。应用使用 Spring Boot 3.5.16，MyBatis Spring Boot Starter 3.0.5；Maven 编译器通过 `release=17` 阻止误用更高版本 JDK API。

## 结果

Java 17 是运行下限，开发机无需降级现有 JDK。Spring Boot 负责受支持组件的依赖管理；MySQL、Testcontainers 与容器镜像仍显式固定版本。升级 Java 或 Spring Boot 必须单独记录 ADR，并重新执行数据库与并发测试。
