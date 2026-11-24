# HikariCP + H2 验证连接池耗尽和恢复

## 适用场景

订单查询在数据库仍然可用时也可能返回获取连接超时。例如同一应用里的两个长事务已经借走池中最后的连接，新事务得等待空闲连接。将池的活跃数、空闲数和获取超时放在一起观察，可以判断当时的阻塞是否发生在借连接这一步。

| 组件 | 分工 |
| --- | --- |
| [HikariCP](../../docs/components/database-connectivity.md#hikaricp) | 复用 JDBC 连接，在池满时等待归还，达到等待上限时抛出异常。 |
| H2 | 执行真实 SQL，保存和读取实验订单。使用 JVM 内存库，运行时无需外部数据库。 |

## 接入步骤

1. 引入 `com.zaxxer:HikariCP:6.3.3` 和目标数据库的 JDBC 驱动。示例使用 H2 2.3.232，并将 `jdbc:h2:mem:orders_pool` 交给 `HikariConfig`。
2. 为共享数据源设置 `maximumPoolSize=2` 和 `connectionTimeout=750`。第一个限制总连接数，第二个限制获取池连接的等待时间，单位为毫秒。示例使用 `getHikariPoolMXBean()` 读取活跃和空闲连接数。
3. 复用 `HikariDataSource`。每次 JDBC 操作使用 try-with-resources 管理借出的 `Connection`、语句和结果集。归还一个连接后，下一个请求可以继续通过同一个池查询订单。

Spring Boot 3.5 应用采用 JDBC 自动配置时，可在已有的 `spring.datasource` 下设置以下参数。这个配置片段依据 Spring Boot 文档编写，本实验直接调用 HikariCP API。

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 2
      connection-timeout: 750
      validation-timeout: 500
```

## 本地验证

安装 JDK 17+，首次运行 Maven Wrapper 时需能下载依赖。从仓库根目录执行。

```sh
./mvnw -B -f labs/hikaricp-pool/pom.xml clean verify
```

| 阶段 | 验证结果 |
| --- | --- |
| 持有两个连接 | 活跃数为 2、空闲数为 0，第三次获取抛出 `SQLTransientConnectionException`。 |
| 归还其中一个 | 从同一个池重新取得连接，查询订单 1001 返回 `PAID`。 |
| 归还所有连接 | 活跃数回到 0。 |

两个连接和 750 毫秒用于复现实验。生产池容量需要结合数据库可用连接数、应用实例数与实际事务持有时间确定。实验覆盖单个连接池和 H2 SQL，外部数据库的网络和故障恢复由部署环境另行验证。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64 上，使用 JDK 17.0.16、HikariCP 6.3.3 和 H2 2.3.232 执行上述 Maven 命令。两次运行中，连接满载时第三次借用分别在 775 和 756 毫秒后超时。归还一个连接后，同一连接池查到订单 1001 的状态为 `PAID`。1 项集成测试通过。

## 官方资料

- [HikariCP 6.3.3](https://github.com/brettwooldridge/HikariCP/releases/tag/HikariCP-6.3.3) · [连接池设置](https://github.com/brettwooldridge/HikariCP#frequently-used)
- [H2 内存数据库](https://h2database.com/html/features.html#in_memory_databases) · [Spring Boot 自动配置数据源](https://docs.spring.io/spring-boot/3.5/reference/data/sql.html)
