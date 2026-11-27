# Spring Scheduling + ShedLock 用数据库协调定时报表

## 适用场景

报表服务有多个副本，Spring 的 `@Scheduled` 会在各副本分别触发相同的任务。希望同一时间只有一个副本生成日报时，各副本可以用 ShedLock 竞争同一个数据库锁名。任务必须能够安全地再次执行。

| 组件 | 分工 |
| --- | --- |
| Spring Scheduling | 按配置的 cron 触发报表方法。 |
| ShedLock | 在调用报表方法前尝试取得指定的锁。锁被占用时跳过这次调用。 |
| Spring JDBC 与数据库 | 锁提供者通过 JDBC 更新 `shedlock` 表。实验另外用 `report_runs` 记录真正运行过的报表。 |

需要持久化触发器、恢复错过的计划时，查看 [Quartz JDBC 补跑](../quartz-jdbc/README.md)。多个执行器要分别领取不同的任务记录时，查看 [MySQL 行锁领任务](../mysql-skip-locked/README.md)。

## 接入步骤

1. 添加 `shedlock-spring`、`shedlock-provider-jdbc-template` 和目标数据库驱动。[实验 POM](pom.xml) 使用 ShedLock 7.12.0、Spring Boot 3.5.16 与 H2 2.3.232。[版本说明](https://github.com/lukas-krecan/ShedLock/blob/shedlock-parent-7.12.0/RELEASES.md)
2. 所有副本连接同一个数据库，在同一张锁表上用 `name` 作主键。参照 [H2 建表语句](src/test/resources/schema.sql) 或官方文档中目标数据库的方言。注册 `LockProvider`，用 `JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(...).usingDbTime().build()` 让锁的时间判断使用数据库 UTC 时间。[JdbcTemplate 配置](https://github.com/lukas-krecan/ShedLock/blob/shedlock-parent-7.12.0/README.md#jdbctemplate)
3. 配置 `@EnableScheduling` 与 `@EnableSchedulerLock`。任务方法同时标注 `@Scheduled` 和 `@SchedulerLock(name = "daily-report")`，所有副本使用同一个锁名。用 `LockAssert.assertLocked()` 核对方法确实经过加锁代理。[Spring 集成与锁断言](https://github.com/lukas-krecan/ShedLock/blob/shedlock-parent-7.12.0/README.md#usage)

本实验的 `lockAtMostFor` 为 10 分钟，`lockAtLeastFor` 使用默认值 0。实际接入时，租约上限应明显长于任务估计的最长运行时间。若任务超过租约，另一个副本可能取得同名锁。需要防止顺序重跑重复写数据时，按业务日期等业务键设计幂等。

## 本地验证

使用 JDK 17，从仓库根目录运行。测试在临时文件 H2 中建表，启动两个独立的 Spring 上下文，各有自己的数据源和 ShedLock 提供者。

```sh
./mvnw -B -f labs/shedlock-jdbc/pom.xml clean test
```

[集成测试](src/test/java/io/github/middlewarelab/shedlock/ShedLockJdbcTest.java) 将两个工作实例的 cron 都设为 `-`，由测试直接调用 Spring 代理上的方法。两个锁存器固定报表任务的交错顺序。[Spring cron 禁用值](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/scheduling/annotation/Scheduled.html#CRON_DISABLED)

| 步骤 | 锁表与报表记录 |
| --- | --- |
| A 进入同名任务并等待 | `locked_by` 为 A，尚无报表记录。 |
| A 持锁期间调用 B | B 跳过本次执行，锁仍由 A 持有，报表表为空。 |
| 让 A 完成，再调用 B | A 先写一条记录。B 的新调用成功，按顺序读到 A、B 各一条记录。 |

## 实测记录

2026-11-27 在 macOS 26.5 ARM64、OpenJDK 17.0.16、Spring JDBC 6.2.19、ShedLock 7.12.0、H2 2.3.232 上运行上述命令。1 项集成测试通过，锁表先记录 `worker-a`，B 跳过时保持不变。A 正常返回后 B 获得同名锁，报表表按插入顺序仅有 `worker-a` 和 `worker-b` 两条记录。

验证范围为单 JVM 中两个独立 Spring 上下文、真实 JDBC 锁表和正常释放。两次 B 调用由测试主动发起，第一次被跳过的调用不会被补跑。
