# 中间件组件目录

先按组件找用途、适用场景、最小接入步骤和官方资料。需要多组件配合时，打开对应的「搭配」条目，查看要解决的问题、组件分工、数据流、对接配置和结果验证。表中标为「可运行」的条目附本仓库实验，标为「联调实测」的组合已运行真实组件并核对输出。

## 消息

| 组件 | 常见用法 |
| --- | --- |
| [RabbitMQ](messaging.md#rabbitmq) | 工作队列、Exchange 路由、发布确认和消费 ACK |
| [Apache ActiveMQ Artemis](messaging.md#apache-activemq-artemis) | 地址、队列、投递与死信。**联调实测** · [搭配 Jakarta JMS 隔离坏消息](../../labs/artemis-jms-dead-letter/README.md)。 |
| [Jakarta JMS](messaging.md#jakarta-jms) | Java 消息发送、消费、确认与事务 API。**联调实测** · [用事务回滚触发重投](../../labs/artemis-jms-dead-letter/README.md)。 |
| [Apache RocketMQ](messaging.md#apache-rocketmq) | 普通、顺序、定时、事务消息，以及重试和死信 |
| [Apache Kafka](messaging.md#apache-kafka) | 分区事件流、消费组、位点提交和历史回放。**可运行** · [Java Client 位点恢复与独立重放](../../labs/kafka-offset-replay/README.md)。 |
| [Apache Pulsar](messaging.md#apache-pulsar) | 持久主题、订阅模式、确认和重投递 |
| [NATS JetStream](messaging.md#nats-jetstream) | Subject 过滤、Stream 保留、持久 Consumer 和 ACK。**联调实测** · [搭配 SQLite 防止积分重复入账](../../labs/nats-jetstream-sqlite/README.md)。 |

**可运行** · [RocketMQ 事务消息实验](../../README.md#run-locally)，包含提交、回滚、事务回查和消费去重。 [RocketMQ 顺序消息实验](messaging.md#订单顺序消息)按订单核对创建、支付和发货的事件序列。

## 应用模块化

| 组件 | 常见用法 |
| --- | --- |
| [Spring Modulith](application-modularity.md#spring-modulith) | 应用内模块边界和事件投递。**可运行** · [模块结构校验](application-modularity.md#在测试中校验订单与积分模块边界)。 |

## 网络编程

| 组件 | 常见用法 |
| --- | --- |
| [Netty](network-programming.md#netty) | Java 异步网络编程、协议编解码及长连接管理。**可运行** · [WebSocket 回显](../../labs/netty-websocket/README.md)、[读空闲时回收长连接](network-programming.md#读空闲时回收长连接)。 |
| [gRPC](network-programming.md#grpc) | 服务间调用、状态码、截止时间和取消。**联调实测** · [订单查询的两跳超时](../../labs/grpc-deadline/README.md)。 |
| [Protocol Buffers](network-programming.md#protocol-buffers) | 消息结构定义、序列化及代码生成。**联调实测** · [生成订单与库存的 RPC 接口](../../labs/grpc-deadline/README.md)。 |

## 搜索、缓存与分析

| 组件 | 常见用法 |
| --- | --- |
| [Elasticsearch（ES）](search-cache.md#elasticsearch) | Mapping、全文检索、聚合、批量写入和分页 |
| [OpenSearch](search-cache.md#opensearch) | 索引管理、检索、聚合与批量操作 |
| [Meilisearch](search-cache.md#meilisearch) | 全文检索、异步索引、过滤和排序。**联调实测** · [商品搜索、库存过滤与价格排序](../../labs/meilisearch-storefront/README.md)。 |
| [Redis](search-cache.md#redis) | 键值缓存、计数、集合、排行、Streams、事务和 Functions。**联调实测** · [广播 Caffeine 失效](../../labs/spring-cache-caffeine/README.md#多实例改价通知与回源合并)。 |
| [Redisson](search-cache.md#redisson) | Java 分布式集合、锁、信号量和租约 |
| [Lettuce](search-cache.md#lettuce) | Java 同步、异步和 Reactive Redis 命令。**联调实测** · [RESP3 价格缓存与失效](../../labs/valkey-client-cache/README.md)。 |
| [Valkey](search-cache.md#valkey) | 键值与数据结构命令、过期和故障转移。**联调实测** · [搭配 Lettuce 按键推送失效](../../labs/valkey-client-cache/README.md)。 |
| [Caffeine](search-cache.md#caffeine) | JVM 进程内缓存、加载和淘汰。**可运行** · [异步刷新](search-cache.md#热点商品详情异步刷新)。**联调实测** · [搭配 Spring Cache 处理商品改价](search-cache.md#搭配-spring-cache-处理商品改价) · [Redis 广播失效](../../labs/spring-cache-caffeine/README.md#多实例改价通知与回源合并)。 |
| [Spring Cache](search-cache.md#spring-cache) | 用注解声明读取、写入和失效策略。**联调实测** · [搭配 Caffeine 处理商品改价](search-cache.md#搭配-spring-cache-处理商品改价)。 |
| [ClickHouse](search-cache.md#clickhouse) | 列式存储、批量写入和分析聚合。**联调实测** · [用 chDB 验证支付增量汇总](../../labs/clickhouse-payment-rollup/README.md)。 |

运行 [Redis 键过期与缓存更新](search-cache.md#键过期与缓存更新)，检查覆盖写入后的 TTL。运行 [Redis 键空间通知](search-cache.md#键空间通知与缓存过期观察)，对照按键和按事件订阅的消息内容。试跑 [Redis Streams 消费组接管](search-cache.md#streams-消费组确认与接管)，核对未确认消息的归属和投递次数。用两个 `redis-cli` 连接复现 [Redis 事务与乐观锁](search-cache.md#事务与乐观锁)的提交冲突和重试。运行 [Redis Functions 单件库存扣减](search-cache.md#redis-functions-单件库存扣减)，从独立连接调用已注册的函数并核对库存余量。

运行 [Redis RDB 快照与重启恢复](search-cache.md#rdb-快照与重启恢复)，核对重启后恢复到哪一次写入。运行 [Redis AOF 写入日志与重启恢复](search-cache.md#aof-写入日志与重启恢复)，通过清单和启动日志核对增量写入的恢复。

## 任务调度

| 组件 | 常见用法 |
| --- | --- |
| [XXL-JOB](scheduling.md#xxl-job) | 执行器注册、Cron、路由、分片广播和失败重试 |
| [PowerJob](scheduling.md#powerjob) | Worker 接入、单机或广播执行、Map/MapReduce 和工作流 |
| [Quartz](scheduling.md#quartz) | Job 与 Trigger、持久化和 misfire。**可运行** · [按 JobKey 串行执行对账任务](scheduling.md#按-jobkey-串行执行对账任务)。**联调实测** · [搭配 Spring Boot 用 JDBC 恢复错过的触发](scheduling.md#搭配-spring-boot-用-jdbc-恢复错过的触发)。 |
| [ElasticJob](scheduling.md#elasticjob) | 注册中心、作业分片、分片执行和故障转移 |
| [Apache DolphinScheduler](scheduling.md#apache-dolphinscheduler) | DAG、定时发布、失败重跑和补数据 |

## 数据库连接与访问

| 组件 | 常见用法 |
| --- | --- |
| [HikariCP](database-connectivity.md#hikaricp) | JDBC 连接复用、获取超时和池状态。**联调实测** · [搭配 H2 验证连接池耗尽](../../labs/hikaricp-pool/README.md)。 |
| [MyBatis](database-connectivity.md#mybatis) | Java Mapper、参数绑定和结果映射。**联调实测** · [搭配 H2 验证库存条件扣减](../../labs/mybatis-stock-update/README.md)。 |

## 数据同步与处理

[SQL 数据同步操作](data-pipelines.md#sql-数据同步)包含首次全量、后续增量和源目标校验。SQL 结构版本管理可查看下表的 Flyway 与 Liquibase。

| 组件 | 常见用法 |
| --- | --- |
| [MySQL Replication](data-pipelines.md#mysql-replication) | 原生主从复制、binlog、GTID、延迟和状态检查 |
| [PostgreSQL Logical Replication](data-pipelines.md#postgresql-logical-replication) | Publication、Subscription、行标识、复制槽和 WAL。**联调实测** · [订单表初始复制、增量与暂停恢复](../../labs/postgres-logical-replication/README.md)。 |
| [Canal](data-pipelines.md#canal) | MySQL binlog 订阅、获取批次与确认 |
| [阿里云 DTS](data-pipelines.md#阿里云-dts) | 托管迁移、全量和增量同步、订阅与任务监控 |
| [Apache Flink](data-pipelines.md#apache-flink) | DataStream 或 SQL、事件时间、窗口和状态恢复。**可运行** · [付款窗口与迟到输出](../../labs/flink-event-time/README.md)。 |
| [Flink CDC](data-pipelines.md#flink-cdc) | 数据库快照、增量捕获、Schema 演进和 Pipeline |
| [Debezium](data-pipelines.md#debezium) | 数据库连接器、变更事件、快照和 offset 恢复。**联调实测** · [搭配 PostgreSQL 读取订单 CDC](../../labs/debezium-postgres-events/README.md)。 |
| [Apache Kafka Connect](data-pipelines.md#apache-kafka-connect) | Source/Sink 插件、Worker、REST 管理与错误处理 |
| [Apicurio Registry](data-pipelines.md#apicurio-registry) | Kafka 消息 Schema 登记、版本与兼容性 |
| [Apache SeaTunnel](data-pipelines.md#apache-seatunnel) | Source、Transform、Sink 数据集成任务 |
| [Apache Camel](data-pipelines.md#apache-camel) | 应用间消息路由、协议连接和异常处理。**联调实测** · [搭配 File 和 HTTP 导入 CSV](../../labs/camel-file-http-import/README.md)。 |
| [Flyway](release-tools.md#flyway) | 版本化 SQL、迁移记录与校验和。**可运行** · [订单表版本化迁移与校验](release-tools.md#订单表版本化迁移与校验)。**联调实测** · [搭配 Spring Boot 自动迁移](release-tools.md#搭配-spring-boot-启动时迁移)。 |
| [Liquibase](release-tools.md#liquibase) | Changelog、SQL 预览、结构变更与版本标签回退。**联调实测** · [搭配 H2 回退订单表](../../labs/liquibase-rollback/README.md)。 |

## 分布式日志

| 组件 | 常见用法 |
| --- | --- |
| [Fluent Bit](distributed-logging.md#fluent-bit) | 采集和转发日志。**可运行** · [合并 Java 异常堆栈](distributed-logging.md#合并-java-异常堆栈)。**联调实测** · [搭配 Loki 查完整异常](distributed-logging.md#搭配-loki-集中检索完整堆栈)。 |
| [Logstash](distributed-logging.md#logstash) | Input、Filter、Output，日志解析、字段处理与持久队列 |
| [Grafana Loki](distributed-logging.md#grafana-loki) | 集中存储、按标签和正文查日志。**联调实测** · [搭配 Fluent Bit 采集 Java 堆栈](distributed-logging.md#搭配-loki-集中检索完整堆栈)。 |
| [Kibana](observability-tools.md#kibana) | Elasticsearch Data View、Discover 与 KQL |

## 优雅上线、灰度发布与探针

| 组件 | 常见用法 |
| --- | --- |
| [Kubernetes Deployment](release-tools.md#kubernetes-deployment) | 分批替换 Pod、滚动状态与修订回滚 |
| [Kubernetes Probes](release-tools.md#kubernetes-probes) | 启动、就绪与存活检查，及对应的摘流或重启 |
| [Spring Boot](release-tools.md#spring-boot) | 应用启动、自动配置和数据源。**联调实测** · [搭配 Flyway 升级订单表](release-tools.md#搭配-spring-boot-启动时迁移) · [搭配 Quartz 重启补跑](scheduling.md#搭配-spring-boot-用-jdbc-恢复错过的触发)。 |
| [Spring Boot Actuator](release-tools.md#spring-boot-actuator) | 健康探针、应用信息与 JVM 指标。**可运行** · [HTTP 探针](release-tools.md#搭配-kubernetes-使用-http-探针) · [导出 Prometheus 指标](release-tools.md#搭配-prometheus-采集应用指标)。 |
| [Argo Rollouts](release-tools.md#argo-rollouts) | 灰度权重、观察与自动分析、晋级或中止 |

## 容器构建

| 组件 | 常见用法 |
| --- | --- |
| [Docker BuildKit](docker-build.md#docker-buildkit) | Dockerfile、JAR 打包、构建上下文、多阶段构建和镜像检查 |
| [Docker Compose](docker-build.md#docker-compose) | 多容器启动、健康检查、依赖顺序和日志 |

## 对象存储

| 组件 | 常见用法 |
| --- | --- |
| [SeaweedFS](object-storage.md#seaweedfs) | S3 对象、bucket 和元数据。**联调实测** · [搭配 AWS SDK 直传报表](../../labs/seaweedfs-s3-presign/README.md)。 |

## 制品与持续交付

| 组件 | 常见用法 |
| --- | --- |
| [Helm](delivery-tools.md#helm) | Chart 渲染、values、安装、升级与回滚。**可运行** · [用 Schema 校验环境覆盖参数](delivery-tools.md#用-schema-校验环境覆盖参数)。 |
| [Kustomize](delivery-tools.md#kustomize) | 复用 Kubernetes 基础清单，按环境设置差异。**联调实测** · [预发与生产 overlay 渲染和校验](../../labs/kustomize-overlays/README.md)。 |
| [kubeconform](delivery-tools.md#kubeconform) | 以目标 Kubernetes 版本的 Schema 校验清单。**联调实测** · [拦截错误副本字段](../../labs/kustomize-overlays/README.md#验证方法)。 |
| [Harbor](delivery-tools.md#harbor) | 镜像推拉、Digest、机器人账号与扫描 |
| [Argo CD](delivery-tools.md#argo-cd) | Git 同步、资源健康、漂移和自动同步 |

## 全链路灰度路由

| 组件 | 常见用法 |
| --- | --- |
| [Istio](traffic-routing.md#istio) | HTTP Header、版本 subset、权重和默认路由 |
| [Apache Dubbo](traffic-routing.md#apache-dubbo) | Tag Router、请求标签与 Provider 分组 |
| [Spring Cloud Gateway](traffic-routing.md#spring-cloud-gateway) | 入口 Header 匹配、灰度路由与默认稳定路由。**联调实测** · [转发到灰度和稳定 HTTP 上游](../../labs/spring-cloud-gateway/README.md)。 |
| [NGINX](traffic-routing.md#nginx) | HTTP 反向代理、按路径路由与响应缓冲控制。**联调实测** · [代理 SSE 订单进度](../../labs/nginx-sse/README.md)。 |

## 服务治理与可观测性

| 组件 | 常见用法 |
| --- | --- |
| [Consul](service-tools.md#consul) | 服务注册、HTTP 健康检查和服务发现。**联调实测** · [发现可用的门店查询实例](../../labs/consul-health-discovery/README.md)。 |
| [Nacos](service-tools.md#nacos) | 服务发现、实例元数据、配置监听与配置灰度 |
| [Sentinel](service-tools.md#sentinel) | 流控、熔断和系统保护。**可运行** · [按商品 ID 限制库存查询](service-tools.md#按商品-id-限制库存查询)。 |
| [etcd](service-tools.md#etcd) | 键值读写、事务、Watch、租约与任务选主。**联调实测** · [租约过期后的接手与写入保护](../../labs/etcd-leader-election/README.md)。 |
| [ZooKeeper](service-tools.md#zookeeper) | znode、会话、版本与 Watch。**联调实测** · [搭配 Curator 监听门店配置](../../labs/curator-zookeeper-cache/README.md)。 |
| [Apache Curator](service-tools.md#apache-curator) | ZooKeeper Java 客户端、会话管理和常用协调组件。**联调实测** · [CuratorCache 单节点快照](../../labs/curator-zookeeper-cache/README.md)。 |
| [Resilience4j](service-tools.md#resilience4j) | Java 调用侧熔断、限流、并发隔离与异步超时。**可运行** · [按异常类型有限重试](service-tools.md#按异常类型有限重试)。**联调实测** · [搭配 Java HttpClient 保护库存查询](../../labs/resilience4j-inventory/README.md)。 |
| [Apache Seata](service-tools.md#apache-seata) | AT、TCC、Saga、XA 与全局事务协调 |
| [OpenTelemetry](service-tools.md#opentelemetry) | Java Agent、追踪、指标、日志与 OTLP 导出。**联调实测** · [Collector 搭配 Tempo 转发 trace](../../labs/otel-collector-tempo/README.md)。 |
| [Prometheus](service-tools.md#prometheus) | 抓取指标、PromQL、记录规则和告警。**可运行** · [测试订单服务抓取失败告警](service-tools.md#测试订单服务抓取失败告警)，含 [pending 状态与等待重置验证](../../labs/prometheus-rules/README.md)。 |
| [Alertmanager](observability-tools.md#alertmanager) | 告警路由、分组、抑制、静默与通知。**联调实测** · [搭配 Prometheus 合并订单告警](../../labs/alertmanager-webhook/README.md)。 |
| [Grafana](observability-tools.md#grafana) | 数据源、Explore 和仪表盘 |
| [Grafana Tempo](observability-tools.md#grafana-tempo) | OTLP 链路存储、Trace ID 与 TraceQL 查询。**联调实测** · [按 Trace ID 查下单链路](../../labs/otel-collector-tempo/README.md)。 |
