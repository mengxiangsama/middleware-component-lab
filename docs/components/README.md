# 中间件组件目录

每个分册包含组件功能、常见用法、最小接入步骤、运行边界、验证方法和官方资料。表中标为「可运行」的条目附本仓库实验，其余可沿分册中的快速开始或 API 文档操作。接入已有环境时，按实际服务端、客户端和连接器版本查阅对应文档。

## 消息

| 组件 | 常见用法 |
| --- | --- |
| [RabbitMQ](messaging.md#rabbitmq) | 工作队列、Exchange 路由、发布确认和消费 ACK |
| [Apache RocketMQ](messaging.md#apache-rocketmq) | 普通、顺序、定时、事务消息，以及重试和死信 |
| [Apache Kafka](messaging.md#apache-kafka) | 分区事件流、消费组、位点提交和历史回放 |
| [Apache Pulsar](messaging.md#apache-pulsar) | 持久主题、订阅模式、确认和重投递 |
| [NATS JetStream](messaging.md#nats-jetstream) | Subject 过滤、Stream 保留、持久 Consumer 和 ACK |

**可运行** · [RocketMQ 事务消息实验](../../README.md#run-locally)，包含提交、回滚、事务回查和消费去重。

## 搜索、缓存与分析

| 组件 | 常见用法 |
| --- | --- |
| [Elasticsearch（ES）](search-cache.md#elasticsearch) | Mapping、全文检索、聚合、批量写入和分页 |
| [OpenSearch](search-cache.md#opensearch) | 索引管理、检索、聚合与批量操作 |
| [Redis](search-cache.md#redis) | 键值缓存、计数、集合、排行和 Streams |
| [Redisson](search-cache.md#redisson) | Java 分布式集合、锁、信号量和租约 |
| [Lettuce](search-cache.md#lettuce) | Java 同步、异步和 Reactive Redis 命令 |
| [Valkey](search-cache.md#valkey) | 键值与数据结构命令、过期和故障转移 |
| [Caffeine](search-cache.md#caffeine) | JVM 进程内缓存、加载、刷新和淘汰 |
| [ClickHouse](search-cache.md#clickhouse) | 列式存储、批量写入和分析聚合 |

## 任务调度

| 组件 | 常见用法 |
| --- | --- |
| [XXL-JOB](scheduling.md#xxl-job) | 执行器注册、Cron、路由、分片广播和失败重试 |
| [PowerJob](scheduling.md#powerjob) | Worker 接入、单机或广播执行、Map/MapReduce 和工作流 |
| [Quartz](scheduling.md#quartz) | Job 与 Trigger、持久化、并发限制和 misfire |
| [ElasticJob](scheduling.md#elasticjob) | 注册中心、作业分片、分片执行和故障转移 |
| [Apache DolphinScheduler](scheduling.md#apache-dolphinscheduler) | DAG、定时发布、失败重跑和补数据 |

## 数据同步与处理

[SQL 数据同步操作](data-pipelines.md#sql-数据同步)包含首次全量、后续增量和源目标校验。SQL 结构版本管理可查看下表的 Flyway 与 Liquibase。

| 组件 | 常见用法 |
| --- | --- |
| [MySQL Replication](data-pipelines.md#mysql-replication) | 原生主从复制、binlog、GTID、延迟和状态检查 |
| [Canal](data-pipelines.md#canal) | MySQL binlog 订阅、获取批次与确认 |
| [阿里云 DTS](data-pipelines.md#阿里云-dts) | 托管迁移、全量和增量同步、订阅与任务监控 |
| [Apache Flink](data-pipelines.md#apache-flink) | DataStream 或 SQL、事件时间、窗口和状态恢复 |
| [Flink CDC](data-pipelines.md#flink-cdc) | 数据库快照、增量捕获、Schema 演进和 Pipeline |
| [Debezium](data-pipelines.md#debezium) | 数据库连接器、变更事件、快照和 offset 恢复 |
| [Apache Kafka Connect](data-pipelines.md#apache-kafka-connect) | Source/Sink 插件、Worker、REST 管理与错误处理 |
| [Apache SeaTunnel](data-pipelines.md#apache-seatunnel) | Source、Transform、Sink 数据集成任务 |
| [Flyway](release-tools.md#flyway) | 版本化 SQL、迁移记录与校验和 |
| [Liquibase](release-tools.md#liquibase) | Changelog、SQL 预览与结构变更 |

## 分布式日志

| 组件 | 常见用法 |
| --- | --- |
| [Fluent Bit](distributed-logging.md#fluent-bit) | Tail、多行合并、解析、过滤和输出缓冲 |
| [Logstash](distributed-logging.md#logstash) | Input、Filter、Output，日志解析、字段处理与持久队列 |
| [Grafana Loki](distributed-logging.md#grafana-loki) | 按标签写入与索引、LogQL 查询 |

## 优雅上线、灰度发布与探针

| 组件 | 常见用法 |
| --- | --- |
| [Kubernetes Deployment](release-tools.md#kubernetes-deployment) | 分批替换 Pod、滚动状态与修订回滚 |
| [Kubernetes Probes](release-tools.md#kubernetes-probes) | 启动、就绪与存活检查，及对应的摘流或重启 |
| [Spring Boot Actuator](release-tools.md#spring-boot-actuator) | 探针端点、SIGTERM 与在途请求处理 |
| [Argo Rollouts](release-tools.md#argo-rollouts) | 灰度权重、观察与自动分析、晋级或中止 |

## 容器构建

| 组件 | 常见用法 |
| --- | --- |
| [Docker BuildKit](docker-build.md#docker-buildkit) | Dockerfile、JAR 打包、构建上下文、多阶段构建和镜像检查 |
| [Docker Compose](docker-build.md#docker-compose) | 多容器启动、健康检查、依赖顺序和日志 |

## 灰度流量路由

| 组件 | 常见用法 |
| --- | --- |
| [Istio](traffic-routing.md#istio) | HTTP Header、版本 subset、权重和默认路由 |
| [Apache Dubbo](traffic-routing.md#apache-dubbo) | Tag Router、请求标签与 Provider 分组 |
| [Spring Cloud Gateway](traffic-routing.md#spring-cloud-gateway) | 入口 Header 匹配、灰度路由与稳定兜底 |

## 服务治理与可观测性

| 组件 | 常见用法 |
| --- | --- |
| [Nacos](service-tools.md#nacos) | 服务发现、实例元数据、配置监听与配置灰度 |
| [Sentinel](service-tools.md#sentinel) | 流控、熔断和降级 |
| [Apache Seata](service-tools.md#apache-seata) | AT、TCC、Saga、XA 与全局事务协调 |
| [OpenTelemetry](service-tools.md#opentelemetry) | Java Agent、追踪、指标、日志与 OTLP 导出 |
| [Prometheus](service-tools.md#prometheus) | 抓取指标、PromQL、记录规则和告警 |
