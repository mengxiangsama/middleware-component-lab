# Spring Boot + Quartz 用 JDBC 恢复错过的触发

## 解决什么问题

门店日结已经登记了对账任务，应用重启时可能错过原计划时间。把任务和触发器存入数据库，新应用就能读回原计划，并按指定的 misfire 策略决定是否补跑。

- Spring Boot 配置 JDBC 数据源、调度器和任务实例。
- Quartz 保存 `JobDetail` 与 `Trigger`，调度器恢复时判断触发是否已经错过。
- 实验中的文件 H2 存放 Quartz 的 `QRTZ_` 调度表和独立的对账执行记录，便于验证任务参数和实际执行结果。

`登记门店和业务日期 → Quartz 持久化触发器 → 关闭并重启应用 → 读回计划 → FireNow 补跑 → 写入对账记录`

## 怎么接

1. 用 [POM](pom.xml) 中的 `spring-boot-starter-quartz`、JDBC starter 和目标库的驱动。实验使用 Spring Boot 3.5.16、Quartz 2.5.2 和 H2 2.3.232。
2. 按目标数据库建立 Quartz 表。将 `spring.quartz.job-store-type` 设为 `jdbc`，前后沿用同一数据源、表前缀和 `spring.quartz.scheduler-name`。示例配置见 [application.properties](src/main/resources/application.properties)。基于 Spring Boot 配置的数据源时，使用自动配置提供的 JDBC JobStore。
3. 用 `JobDataMap` 保存门店 ID 和业务日期。示例设置 `org.quartz.jobStore.useProperties=true`，参数都用字符串。一次性对账任务用 `SimpleTrigger`，选择 `withMisfireHandlingInstructionFireNow()`。[`ReconcileStoreJob`](src/main/java/io/github/quartzlab/ReconcileStoreJob.java) 运行后将门店和业务日期写入实验记录表。

任务与触发器是否跨重启保留由 JobStore 决定。`storeDurably()` 控制任务在没有剩余触发器时是否仍被保留。

实验首次启动才在全新临时库用 `spring.quartz.jdbc.initialize-schema=always`。正式接入可用迁移管理 Quartz 表，应用重复启动时用 `never`。Quartz 部分数据库的标准初始化脚本会删旧表，重复执行可能清掉已登记的触发器。

## 怎么验证

从仓库根目录用 JDK 17+ 运行 [重启集成测试](src/test/java/io/github/quartzlab/QuartzRestartTest.java)。无需 Docker 或外部数据库。

```sh
./mvnw -B -f labs/quartz-jdbc/pom.xml clean verify
```

| 验证阶段 | 应看到的结果 |
| --- | --- |
| 第一次启动应用上下文，写入一个已超出阈值的触发时间来模拟错过 | JDBC JobStore 保存了登记的时间，关闭后 H2 文件仍存在 |
| 用同一个 H2 文件重启并设定 `initialize-schema=never` | 可以按原键读回触发器和原计划时间，执行记录仍为空 |
| 启动调度器 | 监听器收到该触发器的 misfire，Quartz 应用 FireNow 并执行任务 |
| 查看执行结果 | 写入一条 `store-a / 2026-11-22` 记录，一次性触发器已清除 |

本实验覆盖单调度器正常关闭、重启和一次性触发器补跑。运行中的任务如果被强制中断，以及多实例故障转移，需要单独验证。业务对账仍应按门店与业务日期处理幂等。

## 官方资料

- [Quartz 官方仓库](https://github.com/quartz-scheduler/quartz) · [Quartz 2.5.2 发布记录](https://github.com/quartz-scheduler/quartz/releases/tag/v2.5.2)
- [Spring Boot Quartz JDBC 配置](https://docs.spring.io/spring-boot/3.5/reference/io/quartz.html) · [Quartz H2 建表脚本](https://github.com/quartz-scheduler/quartz/blob/v2.5.2/quartz/src/main/resources/org/quartz/impl/jdbcjobstore/tables_h2.sql)
- [Quartz 触发器和 misfire 概念](https://www.quartz-scheduler.org/documentation/quartz-2.5.x/tutorials/tutorial-lesson-04.html) · [SimpleTrigger 的 misfire 策略](https://www.quartz-scheduler.org/documentation/quartz-2.5.x/tutorials/tutorial-lesson-05.html)
