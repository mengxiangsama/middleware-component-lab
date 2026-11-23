# Spring Boot 搭配 Flyway 启动时迁移订单表

## 解决什么问题

旧版订单服务已经有数据。新版读取新增的状态列，发布时需要先升级表结构，并在脚本校验失败时阻止应用进入就绪，避免带着旧结构继续处理请求。

- **Spring Boot** 管理启动生命周期、数据源和 JDBC，启动时自动调用 Flyway。
- **Flyway** 按版本执行 SQL，将版本和校验和写入迁移历史表。
- **H2** 在实验中保存真实表和历史订单。采用文件模式，关闭应用后再启动还能读到同一份数据。

`应用启动 → Spring Boot 初始化数据源 → Flyway 校验并迁移 → 就绪事件 → JDBC 读取订单`

## 怎么接

1. 在 Spring Boot 3.5 的应用依赖中加入 `spring-boot-starter-jdbc` 和 `flyway-core`，再加目标数据库对应的 JDBC 驱动和所需的 Flyway 数据库模块。本实验明确使用 Spring Boot 3.5.16、Flyway 11.8.2 和 H2 2.3.232，沿用仓库原实验的 Flyway 与 H2 版本。
2. 将版本化 SQL 放入应用的 `src/main/resources/db/migration/`。例子复用 [V1 创建订单表](src/test/resources/db/migration/V1__create_orders.sql) 和 [V2 增加状态列](src/test/resources/db/migration/V2__add_order_status.sql) 两个测试脚本。新的变更放进下一版本，保留已经执行过的脚本内容。
3. 配置应用数据源，由 Flyway 管理表结构。使用 Spring Boot 3.5 的标准启动方式即可自动迁移。实验使用文件 H2 的临时路径，复用同一数据源做迁移和业务查询。

```properties
spring.datasource.url=jdbc:h2:file:./data/orders
spring.datasource.username=sa
spring.sql.init.mode=never
spring.flyway.locations=classpath:db/migration
spring.flyway.validate-on-migrate=true
```

上面是本地示例配置。若项目还使用 Hibernate 生成表结构，应一起检查 `ddl-auto`，使同一份 schema 由一套机制维护。生产数据源和凭证按环境注入。

## 怎么验证

从仓库根目录使用 Java 17+ 和 Maven Wrapper 运行 [启动迁移集成测试](src/test/java/io/github/flywaylab/OrderStartupMigrationTest.java)。不需要 Docker，也不监听 HTTP 端口。

```sh
./mvnw -B -f labs/flyway/pom.xml -Dtest=OrderStartupMigrationTest test
```

| 场景 | 可观察结果 |
| --- | --- |
| 旧版运行到 V1，写入订单后由新版启动 | 就绪事件中可查到订单 `101`，金额 `19.90`，新状态为 `PENDING` |
| 用同一个数据库重启新版 | 迁移历史仍只有 V1 和 V2 两个成功版本，数据保持不变 |
| 修改已执行的 V2 SQL 临时副本 | 校验报错，应用启动失败且不会发布 `ApplicationReadyEvent` |

`spring.flyway.target` 仅用来在测试里模拟两个应用版本。测试用独立目录保存 SQL 副本和数据库文件。第二个测试看到 checksum mismatch 是预期结果，应以 Maven 最终测试报告为准。

这组测试覆盖单应用启动与 H2 迁移。部署到 MySQL、PostgreSQL 或多副本环境前，还要在目标库验证 DDL 锁、旧新应用兼容和发布时序。应用降回旧版本不会自动撤销已执行的数据库变更。

## 官方资料

- [Spring Boot 3.5 数据库初始化](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html) · [启动事件生命周期](https://docs.spring.io/spring-boot/3.5/reference/features/spring-application.html#features.spring-application.application-events-and-listeners)
- [Spring Boot 3.5.16 的 Flyway 自动配置测试](https://github.com/spring-projects/spring-boot/blob/v3.5.16/spring-boot-project/spring-boot-autoconfigure/src/test/java/org/springframework/boot/autoconfigure/flyway/FlywayAutoConfigurationTests.java)
- [Flyway 官方仓库](https://github.com/flyway/flyway) · [迁移校验](https://documentation.red-gate.com/flyway/reference/commands/validate)
