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

- Quartz 是可嵌入 Java 应用的调度库。实现 `Job.execute`，创建 `JobDetail` 和 `Trigger`，向 `Scheduler.scheduleJob` 注册后启动。简单周期用 `SimpleTrigger`，日历时间表用 `CronTrigger`。需要持久化或多节点调度时配置 JDBC JobStore，集群节点共享其数据库。[Quartz Job 和 Trigger](https://www.quartz-scheduler.org/documentation/quartz-2.5.x/tutorials/tutorial-lesson-02.html)
- 显式选择 Cron 时区和 misfire 策略，例如错过周期后立即触发或等待下一次。集群节点故障后标记为可恢复的作业可能被其他节点重新执行。
- 验证停机跨过多个计划时间、节点在完成实际写入后退出，以及跨时区和夏令时触发。[Quartz CronScheduleBuilder](https://www.quartz-scheduler.org/api/2.5.x/org/quartz/CronScheduleBuilder.html) · [JDBC 集群与恢复](https://www.quartz-scheduler.org/documentation/quartz-2.5.x/configuration/ConfigJDBCJobStoreClustering.html)

## ElasticJob

- Apache ShardingSphere ElasticJob 提供分布式分片调度。ElasticJob-Lite 用 ZooKeeper 注册协调，Java 作业实现 `SimpleJob`，在 `ShardingContext` 读取分片序号和参数。设置任务名、总分片数、Cron 和 ZooKeeper 命名空间后，用 `ScheduleJobBootstrap` 启动。
- 通过多实例观察分片分配与扩缩容后的重新分配。检查实际版本的 failover 与 misfire 配置和效果，重复调度同一个分片时观察该分片是否重复写入。
- 用少量固定分片启动两实例，验证各分片的处理范围以及实例在作业运行中退出时哪些分片重跑。[ElasticJob 概览](https://shardingsphere.apache.org/elasticjob/current/en/overview/) · [快速开始](https://shardingsphere.apache.org/elasticjob/current/en/quick-start/)

## Apache DolphinScheduler

- 用于数据抽取、加工和发布等多步骤 DAG。创建项目和工作流，按任务类型配置执行环境及参数，连好上下游依赖。发布工作流后手动执行或配置周期调度，查看工作流实例、任务状态和日志。
- 历史补数在工作流运行时指定时间范围。核对串行或并行运行模式、参数传入和并发。失败重跑可能再次执行写入步骤，补数与当期任务同时处理同一分区时也要检查结果。
- 验证上游完成后下游失败、分区部分写入、同一时间窗口同时补跑，以及工作流新版本发布后重跑的任务版本。
- [Apache DolphinScheduler 官方仓库](https://github.com/apache/dolphinscheduler) · [补数与工作流版本能力](https://dolphinscheduler.apache.org/)
