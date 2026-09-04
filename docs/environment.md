# FlashGuard Development Environment

Recorded: 2026-09-04, Asia/Shanghai

## Observed host tools

| Item | Observed value |
| --- | --- |
| Operating system | Windows 11, amd64 (reported by the Java runtime) |
| JDK used by Maven | Oracle JDK 21.0.9 LTS |
| Maven | 3.9.10 |
| Docker client | 28.3.3 |
| Docker Compose | v2.39.2-desktop.1 |
| Docker engine | unavailable during Stage 0 audit; named pipe `//./pipe/docker_engine` did not exist |
| CPU / memory | not recorded because WMI access was denied in the execution sandbox |

The project compiles with `maven.compiler.release=17`. Running Maven on JDK 21 does not change the Java 17 bytecode and API target.

## Locked application and test versions

| Component | Version |
| --- | --- |
| Spring Boot | 3.5.16 |
| MyBatis Spring Boot Starter | 3.0.5 |
| Testcontainers | 2.0.5 |
| MySQL container | 8.4.8 |
| RocketMQ Spring Boot Starter (later stage) | 2.3.6 |

Spring Boot 3.5.16 is the current 3.5 maintenance release observed in the official 3.5 documentation and requires Java 17 or newer. MyBatis 3.0.5 targets the Spring Boot 3.5 line. Testcontainers tests use pinned container tags and do not depend on an arbitrary developer database.

## Reproduction notes

- Docker-dependent verification is not complete until Docker Desktop is running and `docker version` reports both client and server.
- Benchmark reports must add exact Docker Engine, container digest, CPU, memory, JVM flags, and Git commit values from the machine that produced the measurements.
- No performance number is valid merely because the project builds or a reference README reports it.

