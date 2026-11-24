# 任务调度与工作流

调度组件各有计划触发、过期处理、失败重试和人工补跑入口。测试时记录时区、计划触发时间与实际启动时间，观察错过触发、重复调度和跨夏令时的结果。

## XXL-JOB

- 集中管理对账、清理或定时批处理。接入包括调度中心、元数据库和执行器。Java 示例通过 `@XxlJob` 注册 Handler。连接执行器后在调度中心建立任务，填写执行器、Handler 名称、任务参数和触发方式，再手动执行一次查看回调与日志。
- 使用 Cron 或其他可用触发方式。分片广播由各执行器获取分片索引和总数。核对路由、阻塞、过期、超时和重试策略。过期后补触发一次表示追加一次调度，逐日补跑需要通过手工触发传入对应日期或区间。
- 验证暂停数个调度周期后恢复、任务完成后回调丢失、任务超时和执行器扩缩容。调度重试可能产生重复调用，分片广播的范围会随执行器变化。[XXL-JOB 官方文档](https://github.com/xuxueli/xxl-job/blob/master/doc/XXL-JOB%E5%AE%98%E6%96%B9%E6%96%87%E6%A1%A3.md)

## PowerJob

- 支持单机、广播、Map 和 MapReduce 执行，也可定义 DAG 作业依赖。接入时配置 Server 与 Worker 对应应用，实现 Processor，在控制台添加任务并选择执行模式、触发策略和参数，然后手动触发检查实例日志。
- Broadcast 在目标 Worker 上运行。Map 分发子任务，MapReduce 再汇总子任务结果。任务重试或 Worker 离线会改变分片执行时序，分片处理与汇总均可能重复执行。
- 验证部分分片完成后 Worker 退出、重跑时已完成分片的处理、汇总失败后恢复，以及工作流依赖失败时的状态。[PowerJob 官方功能与文档入口](https://github.com/PowerJob/PowerJob)

## Quartz

Quartz 是嵌入 Java 应用的调度库。应用用 `JobDetail` 描述执行内容，用 `Trigger` 描述什么时候执行。单机可以用内存存储，计划需要跨重启时可落到数据库。并发示例使用 Quartz 2.5.0，Spring Boot JDBC 示例使用 2.5.2，两者都用 JDK 17。

| Quartz 2.5.x 功能 | 教程状态 |
| --- | --- |
| 作业定义、参数和一次性触发器（`JobDetail`、`JobDataMap`、`SimpleTrigger`） | 已有教程 · [用 JDBC 保存对账任务](#搭配-spring-boot-用-jdbc-恢复错过的触发)。 |
| 作业并发限制 `@DisallowConcurrentExecution` | 已有教程 · [按 JobKey 串行执行对账任务](#按-jobkey-串行执行对账任务)。 |
| 作业参数更新 `@PersistJobDataAfterExecution` | 联调实测 · [重启后继续处理下一批](../../labs/quartz-jdbc/README.md#重启后继续处理下一批)。 |
| 执行失败处理和监听器 | 待补。 |
| 一次性触发器的 misfire 策略 | 联调实测 · [FireNow 重启补跑](#搭配-spring-boot-用-jdbc-恢复错过的触发)。Cron、时区和日历排除另见 [CronScheduleBuilder](https://www.quartz-scheduler.org/api/2.5.x/org/quartz/CronScheduleBuilder.html)。 |
| JDBC JobStore 持久化 | 联调实测 · [同库重启恢复触发器](#搭配-spring-boot-用-jdbc-恢复错过的触发)。多实例故障转移见 [Quartz 集群文档](https://www.quartz-scheduler.org/documentation/quartz-2.5.x/configuration/ConfigJDBCJobStoreClustering.html)。 |

### 按 JobKey 串行执行对账任务

**适用场景**。定时对账和人工补跑可能同时处理同一家门店的数据。若同一家门店的两次对账写入需要错开，可以让两个 Trigger 引用同一个 `JobDetail`，并在 Job 实现上加 `@DisallowConcurrentExecution`。Quartz 按由名称和组组成的 `JobKey` 限制并发。同一 Job 类可为各门店建立独立的 `JobDetail`，工作线程充足时仍可并行处理不同门店。RAMJobStore 的状态只保存在当前调度器内存中，进程退出后丢失。业务写入仍需要按门店和业务日期实现幂等，避免先后两次执行重复入账。

**接入步骤**。需要 JDK 17、Bash、curl 和可访问 Maven Central 的网络。运行 [Java 示例](../../labs/quartz/JobKeyConcurrencyDemo.java)。它配置 RAMJobStore 和两个工作线程，先让 `store-a` 的定时对账停在锁存器上，再提交引用同一 `JobKey` 的手动补跑，以及引用另一个 `JobKey` 的 `store-b` 对账。应用可在构建文件中声明上述 Quartz 依赖。下面的命令只下载本实验需要的 JAR，SLF4J 使用空日志实现，输出均由示例打印。

**验证方法**。从仓库根目录运行。命令退出时会清理临时下载目录。

```bash
(
    set -e
    quartz_demo_dir=$(mktemp -d)
    trap 'rm -r -- "$quartz_demo_dir"' EXIT
    central=https://repo.maven.apache.org/maven2

    curl -fsSL --retry 3 --retry-all-errors \
        -o "$quartz_demo_dir/quartz-2.5.0.jar" \
        "$central/org/quartz-scheduler/quartz/2.5.0/quartz-2.5.0.jar"
    for module in slf4j-api slf4j-nop; do
        curl -fsSL --retry 3 --retry-all-errors \
            -o "$quartz_demo_dir/$module-2.0.16.jar" \
            "$central/org/slf4j/$module/2.0.16/$module-2.0.16.jar"
    done

    java --class-path "$quartz_demo_dir/*" labs/quartz/JobKeyConcurrencyDemo.java
)
```

2026-11-23 在 macOS arm64、OpenJDK 17.0.16 上运行上述命令，进程退出码为 0。首个 `store-a` 作业仍在运行时，`store-b` 已进入执行，手动补跑触发器处于 `BLOCKED`。放行首个作业后，手动补跑才进入执行。超时或状态不符时，示例以非零状态退出。

```text
store-b ran while store-a was active
store-a manual rerun: BLOCKED
store-a manual rerun started after release
```

**官方文档**。[Quartz 2.5.x 作业定义与并发限制](https://www.quartz-scheduler.org/documentation/quartz-2.5.x/tutorials/tutorial-lesson-03.html) · [RAMJobStore 配置](https://www.quartz-scheduler.org/documentation/quartz-2.5.x/configuration/ConfigRAMJobStore.html) · [工作线程池配置](https://www.quartz-scheduler.org/documentation/quartz-2.5.x/configuration/ConfigThreadPool.html)。

### 搭配 Spring Boot 用 JDBC 恢复错过的触发

门店日结任务需要跨重启保留时，用 Spring Boot 提供的数据源，把 Quartz 的任务和触发器写到 JDBC JobStore。重启保持调度器名和数据库配置一致，Quartz 就能读回原触发器，再按 `FireNow` 补跑一次。用 [门店对账联调](../../labs/quartz-jdbc/README.md) 核对持久化记录、misfire 回调和对账结果。

## Temporal

Temporal 运行带状态的工作流。Workflow 安排步骤，Activity 执行 HTTP 或数据库操作，并可配置每次尝试的超时、重试间隔和次数。Activity 调用写接口时携带稳定的业务幂等键，外部服务用该键识别重试并复用已有结果。[Temporal 重试策略](https://docs.temporal.io/encyclopedia/retry-policies) · [Activity 与幂等性](https://docs.temporal.io/activity-definition)

运行 [Temporal + Go 库存预留实验](../../labs/temporal-activity-retry/README.md)，核对接口先写入再返回 503、第二次请求复用预留号，以及 Workflow 返回的结果。

## ElasticJob

- Apache ShardingSphere ElasticJob 提供分布式分片调度。ElasticJob-Lite 用 ZooKeeper 注册协调，Java 作业实现 `SimpleJob`，在 `ShardingContext` 读取分片序号和参数。设置任务名、总分片数、Cron 和 ZooKeeper 命名空间后，用 `ScheduleJobBootstrap` 启动。
- 通过多实例观察分片分配与扩缩容后的重新分配。检查实际版本的 failover 与 misfire 配置和效果，重复调度同一个分片时观察该分片是否重复写入。
- 用少量固定分片启动两实例，验证各分片的处理范围以及实例在作业运行中退出时哪些分片重跑。[ElasticJob 概览](https://shardingsphere.apache.org/elasticjob/current/en/overview/) · [快速开始](https://shardingsphere.apache.org/elasticjob/current/en/quick-start/)

## Apache DolphinScheduler

- 用于数据抽取、加工和发布等多步骤 DAG。创建项目和工作流，按任务类型配置执行环境及参数，连好上下游依赖。发布工作流后手动执行或配置周期调度，查看工作流实例、任务状态和日志。
- 历史补数在工作流运行时指定时间范围。核对串行或并行运行模式、参数传入和并发。失败重跑可能再次执行写入步骤，补数与当期任务同时处理同一分区时也要检查结果。
- 验证上游完成后下游失败、分区部分写入、同一时间窗口同时补跑，以及工作流新版本发布后重跑的任务版本。
- [Apache DolphinScheduler 官方仓库](https://github.com/apache/dolphinscheduler) · [补数与工作流版本能力](https://dolphinscheduler.apache.org/)
