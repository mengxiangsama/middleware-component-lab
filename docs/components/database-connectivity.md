# 数据库连接与 JDBC

## HikariCP

HikariCP 是 JVM 内的 JDBC 连接池。应用向同一个 `DataSource` 借取连接，执行查询或提交事务后关闭 JDBC 句柄，把连接归还池中。连接复用可以减少建连开销，池上限控制同时占用数据库的连接数。

- **最小接入**。在 JDBC 项目中声明 `com.zaxxer:HikariCP` 和目标数据库的 JDBC 驱动，创建一个 `HikariDataSource`。设置 JDBC URL、认证、`maximumPoolSize` 和 `connectionTimeout`。Spring Boot 项目使用自动配置时，通过 `spring.datasource.hikari` 设置连接池参数。[HikariCP 配置](https://github.com/brettwooldridge/HikariCP#frequently-used) · [Spring Boot 数据源](https://docs.spring.io/spring-boot/3.5/reference/data/sql.html)
- **定位连接耗尽**。若活跃数触到上限、空闲数降到零，并且出现排队或获取连接超时，检查事务持有时间、慢查询和连接归还情况。获取连接的超时由连接池控制，SQL 的执行耗时和锁等待由 JDBC 驱动及数据库的配置约束。[连接池统计](https://github.com/brettwooldridge/HikariCP/wiki/MBean-(JMX)-Monitoring-and-Management)
- **联调验证**。运行 [HikariCP + H2 订单查询](../../labs/hikaricp-pool/README.md)，观察连接都借出时的获取超时，并核对归还连接后订单查询恢复。
