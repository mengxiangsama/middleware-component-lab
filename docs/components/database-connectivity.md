# 数据库连接与 JDBC

## HikariCP

HikariCP 是 JVM 内的 JDBC 连接池。应用向同一个 `DataSource` 借取连接，执行查询或提交事务后关闭 JDBC 句柄，把连接归还池中。连接复用可以减少建连开销，池上限控制同时占用数据库的连接数。

- **最小接入**。在 JDBC 项目中声明 `com.zaxxer:HikariCP` 和目标数据库的 JDBC 驱动，创建一个 `HikariDataSource`。设置 JDBC URL、认证、`maximumPoolSize` 和 `connectionTimeout`。Spring Boot 项目使用自动配置时，通过 `spring.datasource.hikari` 设置连接池参数。[HikariCP 配置](https://github.com/brettwooldridge/HikariCP#frequently-used) · [Spring Boot 数据源](https://docs.spring.io/spring-boot/3.5/reference/data/sql.html)
- **定位连接耗尽**。若活跃数触到上限、空闲数降到零，并且出现排队或获取连接超时，检查事务持有时间、慢查询和连接归还情况。获取连接的超时由连接池控制，SQL 的执行耗时和锁等待由 JDBC 驱动及数据库的配置约束。[连接池统计](https://github.com/brettwooldridge/HikariCP/wiki/MBean-(JMX)-Monitoring-and-Management)
- **联调验证**。运行 [HikariCP + H2 订单查询](../../labs/hikaricp-pool/README.md)，观察连接都借出时的获取超时，并核对归还连接后订单查询恢复。

## MyBatis

MyBatis 将 Java 接口方法映射到 SQL。通过 XML 或注解配置语句，用 JDBC 参数绑定传入值并映射结果。接入时配置数据源、创建 `SqlSessionFactory` 并注册 Mapper。写入操作可检查受影响行数，用于识别执行结果是否满足业务条件。[Java API](https://mybatis.org/mybatis-3/java-api.html)

运行 [MyBatis + H2 库存条件扣减](../../labs/mybatis-stock-update/README.md)，核对两次并发更新的影响行数和最终库存。

## H2

H2 是 Java SQL 数据库，可用 JDBC 以嵌入式方式运行。需要在重新打开连接后保留数据时，用 `jdbc:h2:file:...` 指向本地文件。业务表与框架元数据表可以放在同一个数据库，由同一个事务提交。[H2 数据库 URL](https://h2database.com/html/features.html#database_url)

在 [Spring Batch 发货导入](scheduling.md#搭配-h2-续跑发货对账导入)中，H2 保存 CSV 的导入结果和步骤检查点。实验重新打开文件库后读取已提交行，再按持久化进度续跑。[运行方法](../../labs/spring-batch-chunk-restart/README.md)

## SQLite

SQLite 将关系数据存入本地文件。适合单机工具、门店应用和本机任务。Java 通过 Xerial SQLite JDBC 打开文件，多个连接共享同一数据库。启用 WAL 后，读事务可以保留自己的快照，同时一个写事务仍能提交。[Xerial JDBC](https://github.com/xerial/sqlite-jdbc) · [SQLite WAL](https://www.sqlite.org/wal.html)

运行 [SQLite + JDBC 发货报表快照](../../labs/sqlite-wal-snapshot/README.md)，核对写入前后的两套读结果，以及第二个写事务和旧快照升级写事务时的错误码。

## MySQL InnoDB

InnoDB 支持行级加锁。任务表可以用 `SELECT ... FOR UPDATE SKIP LOCKED` 让多个事务领取各自可见的可用行，在同一事务更新状态。调用方需要用合适的索引限制扫描范围，并处理事务失败后的重试与领取后的业务恢复。[MySQL 锁定读](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)

运行 [MySQL + JDBC 发货任务领取实验](../../labs/mysql-skip-locked/README.md)，核对 A 持锁、B 领取另一行，以及 A 回滚后 C 接手。需要复制数据时另见 [MySQL Replication](data-pipelines.md#mysql-replication)。
