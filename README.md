# Middleware Component Lab

[![CI](https://github.com/mengxiangsama/middleware-component-lab/actions/workflows/ci.yml/badge.svg)](https://github.com/mengxiangsama/middleware-component-lab/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

本仓库按组件介绍用途、适用场景、接入方法和常见搭配。组合用法从实际问题出发，写清组件分工、数据怎么走，以及如何验证结果。

## 从哪里开始

| 你想了解什么 | 入口 |
| --- | --- |
| 某个组件能做什么、怎么接、能和谁搭配 | [组件目录](docs/components/README.md) |
| 手头的问题要用哪些组件一起解决 | [按业务场景查学习路线](docs/learning-path.md) |
| 订单落库后怎样可靠地向其他服务发布消息 | [RocketMQ 事务消息实验](#run-locally)，启动 Broker 和示例应用，用 HTTP 请求观察提交、回滚、事务回查和消费去重。 |
| 订单支付消息重投后怎样避免积分重复发放 | [NATS JetStream + SQLite](labs/nats-jetstream-sqlite/README.md)，实测 Worker 提交后断开、Broker 重投与数据库去重。 |
| 两个 Worker 更新同一个批次进度时怎样避免覆盖 | [NATS JetStream KV + Go](labs/nats-kv-cas/README.md)，运行 `(cd labs/nats-kv-cas && go run -mod=readonly .)` 检查版本冲突与重试。 |
| 发货消费者重启与补数时怎样控制读取位置 | [Kafka 消费位点恢复与独立重放](labs/kafka-offset-replay/README.md)，运行 `./mvnw -B -f labs/kafka-offset-replay/pom.xml clean verify`。 |
| 多个发货消费者怎样分摊订单并保持同一订单的事件顺序 | [Pulsar Key_Shared + Java](labs/pulsar-key-shared/README.md)，运行 `sh labs/pulsar-key-shared/verify.sh` 核对同键路由和状态顺序，并验证 [A 掉线后 B 接手未确认事件](labs/pulsar-key-shared/README.md#auto_split-消费者接手)。 |
| 坏的订单消息总是处理失败时怎样隔离 | [ActiveMQ Artemis + Jakarta JMS](labs/artemis-jms-dead-letter/README.md)，运行 `./mvnw -B -f labs/artemis-jms-dead-letter/pom.xml clean verify` 核对重投、死信与正常提交。 |
| 收到 Broker 确认后怎样核对消息确实有路由 | [RabbitMQ + Java 发布确认](labs/rabbitmq-publisher-confirms/README.md)，运行 `sh labs/rabbitmq-publisher-confirms/verify.sh` 对照入队和 `NO_ROUTE` 返回。 |
| 设备短时断网后怎样接回离线指令 | [Mosquitto + Paho MQTT](labs/mosquitto-offline-qos1/README.md)，安装实验依赖后运行 `python labs/mosquitto-offline-qos1/verify.py` 核对会话恢复。 |
| 同一服务内，订单支付后怎样给用户发积分 | [Spring Modulith 订单积分实验](#spring-modulith-订单积分实验)，用集成测试复现失败重投、重复投递和投递记录清理。 |
| 客户端怎样建立长连接并双向收发消息 | [Netty WebSocket 回显实验](labs/netty-websocket/README.md)，建立连接、核对文本回显并正常关闭。 |
| 订单服务调用库存时怎样共用请求的超时预算 | [gRPC + Protocol Buffers](labs/grpc-deadline/README.md)，运行 `./mvnw -B -f labs/grpc-deadline/pom.xml clean verify` 验证两跳调用和取消。 |
| 发货资格检查变慢时怎样查达标次数 | [Micrometer Timer + Actuator + Prometheus](labs/micrometer-actuator-timer/README.md)，查询检查通过和拒绝的次数及耗时桶。 |
| 告警上线前怎样验证等待、触发与恢复 | [Prometheus 告警规则实验](labs/prometheus-rules/README.md)，用固定样本检查短暂故障、持续故障和等待时间重置。 |
| 两个订单实例同时出故障时怎样只发一条告警 | [Prometheus + Alertmanager](labs/alertmanager-webhook/README.md)，在本地 Webhook 核对合并后的触发和恢复通知。 |
| 发布前怎样发现 Helm 环境参数填错 | [Helm 参数校验实验](labs/helm-values-schema/README.md)，用 `values.schema.json` 检查端口类型、取值范围和键名拼写。 |
| 仓库操作员怎样只查询本仓库的运单 | [Open Policy Agent + Python HTTP 网关](labs/opa-shipment-authorization/README.md)，安装 OPA 1.23.0 后运行 `OPA_BIN=opa python3 -B -m unittest discover -s labs/opa-shipment-authorization -v` 核对授权与失败响应。 |
| 同一结算服务怎样只对指定门店启用新功能 | [OpenFeature + flagd](labs/openfeature-flagd/README.md)，运行 `sh labs/openfeature-flagd/verify.sh` 核对试点门店、普通门店和缺少旗标三种结果。 |
| 预发和生产怎样共用发布清单并拦截错误字段 | [Kustomize + kubeconform](labs/kustomize-overlays/README.md)，对照环境差异，并用 Kubernetes Schema 校验渲染结果。 |
| 新版应用如何在启动时升级旧订单表 | [Spring Boot + Flyway](labs/flyway/README.md)，验证自动迁移、重启和脚本校验失败时的启动行为。 |
| 发版失败时怎样撤销新列并保留旧订单 | [Liquibase + H2 按标签回退](labs/liquibase-rollback/README.md)，运行 `./mvnw -B -f labs/liquibase-rollback/pom.xml clean verify` 检查列结构、订单数据和迁移记录。 |
| 订单表怎样从主库同步到报表库 | [PostgreSQL 逻辑复制](labs/postgres-logical-replication/README.md)，运行 `./mvnw -B -f labs/postgres-logical-replication/pom.xml clean verify` 验证全量、增量和暂停恢复。 |
| 下游怎样从订单表读到新增、修改和删除事件 | [Debezium + PostgreSQL](labs/debezium-postgres-events/README.md)，运行 `./mvnw -B -f labs/debezium-postgres-events/pom.xml clean verify`，核对快照和 CDC 事件。 |
| 保存发货状态时怎样让下游只收到已提交的事件 | [PostgreSQL 事务 Outbox + Debezium](labs/debezium-postgres-outbox/README.md)，运行 `./mvnw -B -f labs/debezium-postgres-outbox/pom.xml clean verify` 核对事务和事件映射。 |
| Worker 重启后怎样继续传输新增的发货文件记录 | [Kafka Connect FileStream + Kafka](labs/kafka-connect-filestream/README.md)，运行 `python3 labs/kafka-connect-filestream/verify.py /path/to/kafka_2.13-4.4.0` 核对输出和两端位点。 |
| 订单消息增加字段时怎样检查旧消息仍可读 | [Apicurio Registry + Avro](labs/apicurio-avro-compatibility/README.md)，运行 `python3 labs/apicurio-avro-compatibility/verify.py /path/to/quarkus-run.jar` 核对版本兼容与拒绝。 |
| 支付看板怎样增量汇总并跨批去重 | [ClickHouse + chDB](labs/clickhouse-payment-rollup/README.md)，用 Python 核对北京时间分日、回填和买家去重。 |
| 怎样在本机核对指定日期的 Parquet 订单 | [DuckDB + Parquet](labs/duckdb-parquet-report/README.md)，安装实验依赖后运行 `python labs/duckdb-parquet-report/verify.py` 检查分区筛选与金额。 |
| 支付事件乱序到达时怎样按付款时间汇总 | [Flink 事件时间窗口](labs/flink-event-time/README.md)，运行 `./mvnw -B -f labs/flink-event-time/pom.xml clean verify` 核对两个窗口和迟到输出。 |
| 订单查询拿不到数据库连接时怎样定位 | [HikariCP + H2](labs/hikaricp-pool/README.md)，运行 `./mvnw -B -f labs/hikaricp-pool/pom.xml clean verify` 验证连接池满载和归还后的恢复。 |
| 报表读取期间怎样继续登记新发货 | [SQLite + JDBC 读写快照](labs/sqlite-wal-snapshot/README.md)，运行 `./mvnw -B -f labs/sqlite-wal-snapshot/pom.xml clean verify` 核对报表金额与写入竞争。 |
| 多个发货 Worker 怎样从同一张表领取任务 | [MySQL + JDBC 跳过锁定任务](labs/mysql-skip-locked/README.md)，运行 `sh labs/mysql-skip-locked/verify.sh` 核对并行领取和回滚后的接手。 |
| CSV 导入接口临时失败时怎样重投和归档 | [Camel File + HTTP](labs/camel-file-http-import/README.md)，运行 `./mvnw -B -f labs/camel-file-http-import/pom.xml clean verify` 验证重投及成功后的文件移动。 |
| 怎样把完整的对账 CSV 交给合作方读取 | [Apache MINA SSHD + SFTP](labs/sftp-staged-upload/README.md)，运行 `./mvnw -B -ntp -f labs/sftp-staged-upload/pom.xml test` 核对主机密钥、临时文件和最终内容。 |
| 两个请求同时购买最后一件商品怎样防止超卖 | [MyBatis + H2 条件更新](labs/mybatis-stock-update/README.md)，运行 `./mvnw -B -f labs/mybatis-stock-update/pom.xml clean verify` 核对成功次数和最终余量。 |
| 多个 Spring 应用副本怎样避免同时跑同一份日报 | [Spring Scheduling + ShedLock + JDBC](labs/shedlock-jdbc/README.md)，运行 `./mvnw -B -f labs/shedlock-jdbc/pom.xml clean test` 核对持锁时跳过和释放后的执行。 |
| 应用重启后怎样补跑错过的门店对账 | [Spring Boot + Quartz JDBC](labs/quartz-jdbc/README.md)，核对原触发器、misfire 和补跑记录。 |
| 定时任务重启后怎样从下一批接着跑 | [Quartz JDBC 作业进度](labs/quartz-jdbc/README.md#重启后继续处理下一批)，运行 `./mvnw -B -f labs/quartz-jdbc/pom.xml -Dtest=QuartzJobCheckpointTest test` 验证游标恢复。 |
| 发货 CSV 导入中途失败时怎样从上次提交处续跑 | [Spring Batch + H2](labs/spring-batch-chunk-restart/README.md)，运行 `./mvnw -B -f labs/spring-batch-chunk-restart/pom.xml clean verify` 核对失败批次回滚与续跑。 |
| 库存接口已写入却返回 503，流程怎样安全重试 | [Temporal + Go Activity](labs/temporal-activity-retry/README.md)，启动本地 Temporal 后运行 `(cd labs/temporal-activity-retry && go run -mod=readonly .)`。 |
| 多个日结进程怎样选出当前执行者 | [etcd 租约选主](labs/etcd-leader-election/README.md)，实测接手和旧执行者的条件写入拒绝。 |
| 后台改门店接单上限后怎样更新本地配置 | [ZooKeeper + Apache Curator](labs/curator-zookeeper-cache/README.md)，运行 `./mvnw -B -f labs/curator-zookeeper-cache/pom.xml clean verify` 核对初始加载、更新和删除。 |
| 门店查询实例故障后怎样从可用列表摘除 | [Consul + HTTP 服务发现](labs/consul-health-discovery/README.md)，运行 `python3 labs/consul-health-discovery/verify.py --consul /absolute/path/to/consul` 验证健康筛选和恢复。 |
| 怎样检查应用健康和 JVM 指标 | [Actuator 健康探针](#actuator-健康探针) · [应用信息和指标](#应用信息和指标) |
| 监控端能抓到指标时，怎样发现应用其实未就绪 | [Prometheus + Blackbox Exporter](labs/prometheus-blackbox-http/README.md)，准备两个官方二进制后运行 `python3 labs/prometheus-blackbox-http/verify.py`，对照抓取、探测与状态码。 |
| Java 集群的共享内存数据怎样在一个节点退出后接管 | [Hazelcast IMap + Java](labs/hazelcast-imap-backup/README.md)，运行 `./mvnw -B -f labs/hazelcast-imap-backup/pom.xml clean verify` 核对同步备份和接管。 |
| 商品改价后怎样更新多实例缓存 | [Spring Cache + Caffeine](labs/spring-cache-caffeine/README.md) · [Redis 广播失效与并发读价](labs/spring-cache-caffeine/README.md#多实例改价通知与回源合并) |
| 商品价格怎样本地命中并跟踪改价 | [Valkey + Lettuce 客户端缓存](labs/valkey-client-cache/README.md)，核对 RESP3 失效前后的服务端 GET 次数。 |
| 商品搜索怎样筛出有库存的结果并按价格排序 | [Meilisearch 商品搜索实验](labs/meilisearch-storefront/README.md)，运行 `python3 labs/meilisearch-storefront/verify.py` 核对异步写入、过滤和价格顺序。 |
| 对账导出翻页时怎样避免被新的订单变更打乱 | [OpenSearch + Python PIT 分页](labs/opensearch-pit-pagination/README.md)，运行 `sh labs/opensearch-pit-pagination/run.sh /path/to/opensearch-3.10.0`，对照快照和最新查询。 |
| 多个报价服务实例怎样共享供应商调用额度 | [Redisson + Valkey 全局限流](labs/redisson-shared-rate/README.md)，运行 `VALKEY_SERVER=/absolute/path/to/valkey-server ./mvnw -B -f labs/redisson-shared-rate/pom.xml clean verify`。 |
| 库存接口响应很慢时怎样实测 Java 请求超时 | [Toxiproxy + Java HttpClient](labs/toxiproxy-http-timeout/README.md)，准备 JDK 17 和 Toxiproxy 2.13.0 后运行 `java labs/toxiproxy-http-timeout/HttpTimeoutDemo.java /path/to/toxiproxy-server`。 |
| 库存接口持续返回 503 时怎样限制重试并恢复 | [Resilience4j + Java HttpClient](labs/resilience4j-inventory/README.md)，检查熔断后下游请求数和半开恢复。 |
| 怎样让带标记的商品查询走灰度版本 | [Spring Cloud Gateway Header 路由实验](labs/spring-cloud-gateway/README.md)，运行 `./mvnw -B -f labs/spring-cloud-gateway/pom.xml verify` 核对实际转发。 |
| 订单状态怎样通过代理逐条到达客户端 | [NGINX + SSE 流式响应实验](labs/nginx-sse/README.md)，运行 `python3 labs/nginx-sse/verify.py` 核对首条事件在流结束前到达。 |
| 首选库存实例不就绪时怎样自动切到备用 | [Caddy + HTTP 就绪探针](labs/caddy-active-health/README.md)，准备 Python 3.10+ 和 Caddy 2.11.9 后运行 `python3 -B labs/caddy-active-health/verify.py --caddy /path/to/caddy`。 |
| 本地 HTTP API 怎样通过 HTTPS 入口转发 | [Caddy + Python HTTP API](labs/caddy-tls-proxy/README.md)，运行 `python3 labs/caddy-tls-proxy/verify.py --caddy /absolute/path/to/caddy` 核对证书、转发和路径匹配。 |
| 怎样让门店把对账 CSV 直接上传到对象存储 | [SeaweedFS + AWS SDK 预签名 URL](labs/seaweedfs-s3-presign/README.md)，运行 `(cd labs/seaweedfs-s3-presign && WEED_BINARY=/absolute/path/to/weed go test -count=1 -v ./...)`。 |
| 订单报错后怎样集中检索完整 Java 堆栈 | [Fluent Bit + Loki](labs/fluent-bit-java-multiline/README.md#搭配-loki-集中检索完整堆栈)，从文件采集到按服务查异常，也可以[单独验证多行合并](labs/fluent-bit-java-multiline/README.md#验证方法)。 |
| 发货日志怎样筛选字段并区分坏记录 | [Vector + HTTP 日志接收端](labs/vector-log-routing/README.md)，运行 `python3 labs/vector-log-routing/verify.py --vector /absolute/path/to/vector` 核对转发内容与拒绝原因。 |
| 订单服务和库存服务怎样接出同一条调用链 | [OpenTelemetry Java + JDK HTTP](labs/otel-java-http-propagation/README.md)，运行 `./mvnw -B -f labs/otel-java-http-propagation/pom.xml clean verify` 核对 `traceparent` 与 span 父子关系。 |
| 下单变慢时怎样查入口和库存调用各花多久 | [OpenTelemetry Collector + Grafana Tempo](labs/otel-collector-tempo/README.md)，按一个 Trace ID 核对两段 span 的父子关系和耗时。 |

这些 Java 实验都需要 Java 17+，在仓库根目录运行自带的 Maven Wrapper。RocketMQ 事务消息的端到端演示还需要 Docker Compose，Spring Modulith 的测试使用内嵌 H2。Prometheus 规则实验只需 Docker 和 Shell，运行 `sh labs/prometheus-rules/verify.sh`。

在 Python 虚拟环境安装 `labs/clickhouse-payment-rollup/requirements.txt` 中的依赖，然后运行 `python labs/clickhouse-payment-rollup/verify.py`，对账 ClickHouse 原始明细和增量报表。

使用 Go 1.24+ 和 C 编译器，在仓库根目录运行 `(cd labs/nats-jetstream-sqlite && CGO_ENABLED=1 go run .)`，验证 JetStream 重投时积分只入账一次。

使用 Go 1.24+ 运行 `(cd labs/etcd-leader-election && go run .)`，验证 etcd 租约过期后的任务接手和写入保护。

将 Prometheus 3.16.0 和 Alertmanager 0.35.0 加入 `PATH` 后，在仓库根目录运行 `python3 labs/alertmanager-webhook/verify.py`，验证真实抓取到 Webhook 的告警链路。

发货耗时实验需要 JDK 17+、Python 3 和 Prometheus 3.16.0。先运行 `./mvnw -B -ntp -f labs/micrometer-actuator-timer/pom.xml clean package`，再运行 `python3 labs/micrometer-actuator-timer/verify.py /path/to/prometheus`。

Helm 参数校验实验需要 Helm 4.3.2 和 Shell。在仓库根目录运行 `sh labs/helm-values-schema/verify.sh`，无需 Kubernetes 集群。

将 Kustomize 5.8.1 和 kubeconform 0.8.0 加入 `PATH` 后，运行 `sh labs/kustomize-overlays/verify.sh`，验证预发和生产渲染，以及错误副本字段拦截。

在仓库根目录运行 `./mvnw -B -f labs/flyway/pom.xml -Dtest=OrderStartupMigrationTest test` 可实测 Spring Boot 启动迁移，使用内嵌 H2。

运行 `./mvnw -B -f labs/quartz-jdbc/pom.xml clean verify` 可验证 Quartz 用同一个 H2 文件库跨重启补跑，无需 Docker。

运行 `./mvnw -B -f labs/spring-cache-caffeine/pom.xml test` 可联调商品缓存与数据库事务，使用内嵌 H2。用本地 Redis 验证双实例时，执行 `./mvnw -B -f labs/spring-cache-caffeine/pom.xml -Dtest=RedisPriceCacheIT -Dlab.redis.port=16379 test`。

运行 `VALKEY_SERVER=/absolute/path/to/valkey-server ./mvnw -B -f labs/valkey-client-cache/pom.xml verify`，验证 Valkey 与 Lettuce 的缓存命中、改价失效和重新加载。

在仓库根目录运行 `sh labs/fluent-bit-java-multiline/verify.sh` 可验证多行采集，需要 Docker、Shell 和 `jq`。运行 `sh labs/fluent-bit-java-multiline/verify-loki.sh` 可联调 Loki 写入与查询，另需 Docker Compose 和 `curl`。

将 `otelcol` 和 `tempo` 加入 `PATH` 后，在仓库根目录运行 `python3 labs/otel-collector-tempo/verify.py` 验证 OTLP 转发和 Trace ID 查询。

## RocketMQ 事务消息实验

根项目用原生 RocketMQ 客户端模拟跨服务的订单事件投递，生产者和消费者都在同一个示例应用里，方便查看日志。生产者先发送半消息，再执行本地事务，将订单和事务结果写入 H2。Broker 收到提交结果后才投递消息，遇到未知状态时向生产者回查。消费者用消息 ID 在 `consumed_event` 表中去重。

事务消息协调本地事务与消息发布的最终一致性。下游消费有自己的事务边界，需要支持重试和幂等处理。

### Run locally

从仓库根目录启动 NameServer 和 Broker。Compose 的 `topic-init` 会创建事务主题 `order-transaction-topic`。

```bash
docker compose up -d
docker compose ps -a
```

确认 `broker` 为 `healthy`，`topic-init` 以状态码 0 退出，再启动应用。

```bash
./mvnw spring-boot:run
```

应用默认监听 `http://localhost:8088`。在另一个终端分别发送下面的请求，记录响应中的 `orderId`。

#### 1. 提交订单

```bash
curl -sS http://localhost:8088/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"productName":"Mechanical Keyboard","amount":399.00,"outcome":"COMMIT"}'
```

查询订单返回 200，应用日志会记录这笔订单的消费结果。

#### 2. 模拟回滚

```bash
curl -sS http://localhost:8088/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"productName":"Rollback Demo","amount":1.00,"outcome":"ROLLBACK"}'
```

查询订单返回 404，Broker 不向消费者投递这条消息。

#### 3. 触发事务回查

```bash
curl -sS http://localhost:8088/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"productName":"Checkback Demo","amount":9.90,"outcome":"UNKNOWN"}'
```

本地事务已提交，监听器故意返回 RocketMQ 客户端的 `UNKNOW` 状态。Broker 回查时，生产者从 `local_transaction` 读取结果并确认提交，随后可以在应用日志中看到消费记录。

用响应中的订单 ID 查询本地状态。Broker 日志可辅助排查主题和投递问题。

```bash
ORDER_ID=replace-with-returned-order-id
curl -sS -i "http://localhost:8088/api/orders/$ORDER_ID"
docker compose logs -f broker
```

### 测试与打包

从仓库根目录运行测试并打包 JAR。根 POM 对应 RocketMQ 实验，测试本身不需要运行 Broker。

```bash
./mvnw clean verify
```

需要构建本地镜像时，在打包成功后执行下面的命令。镜像构建和检查详见 [Dockerfile 与 Compose](docs/components/docker-build.md)。

```bash
docker build -t rocketmq-transaction-lab:local .
```

### Actuator 健康探针

示例已引入 `spring-boot-starter-actuator` 并启用两个探针。按上面的步骤启动应用后，在另一终端请求默认端口 `8088`。

```sh
curl -i http://localhost:8088/actuator/health/liveness
curl -i http://localhost:8088/actuator/health/readiness
```

应用正常就绪后，两个请求均返回 HTTP 200 和 `{"status":"UP"}`。[依赖与 Kubernetes 接入](docs/components/release-tools.md#搭配-kubernetes-使用-http-探针)。

#### 应用信息和指标

示例还开放 `info`、`metrics` 和 `prometheus`。分别查看应用名、单项 JVM 指标和 Prometheus 抓取格式的指标。

```sh
curl -i http://localhost:8088/actuator/info
curl -i http://localhost:8088/actuator/metrics/jvm.memory.used
curl -i http://localhost:8088/actuator/prometheus
```

运行 `./mvnw -B -Dtest=ActuatorEndpointsTest test` 可在不连接 Broker 的情况下验证上述端点和两个健康探针。[对接 Prometheus](docs/components/release-tools.md#搭配-prometheus-采集应用指标)。

### 配置

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `SERVER_PORT` | `8088` | HTTP 服务端口 |
| `ROCKETMQ_NAME_SERVER` | `127.0.0.1:9876` | NameServer 地址 |
| `ROCKETMQ_TOPIC` | `order-transaction-topic` | 事务主题 |
| `ROCKETMQ_PRODUCER_GROUP` | `order-transaction-producer` | 事务生产者组 |
| `ROCKETMQ_CONSUMER_GROUP` | `order-created-consumer` | 订单事件消费组 |
| `ROCKETMQ_ENABLED` | `true` | 是否启用 RocketMQ 客户端配置 |

接入实际业务时，使用持久数据库并保留可供回查的事务结果，监控长期未决事务，为重复发布的业务事件选择稳定的幂等键。

## Spring Modulith 订单积分实验

订单和积分是同一个应用内的两个模块。支付事务登记 `OrderPaid` 事件，提交后积分模块在独立事务中入账。集成测试验证失败后可重投原记录，同一订单和奖励类型只入账一次，以及按完成时间清理过期的已完成投递记录，保留待重投和近期完成的记录。

从仓库根目录使用独立 POM 运行实验。复现步骤和清理 API 见[实验文档](labs/spring-modulith/README.md)。

```bash
./mvnw -f labs/spring-modulith/pom.xml clean verify
```

## 组件手册

[完整组件目录](docs/components/README.md)按类别列出分册和对应组件。

| 想查什么 | 分册与常见组件 |
| --- | --- |
| 单体应用的模块边界与事件投递 | [应用模块化](docs/components/application-modularity.md) · Spring Modulith |
| 消息发布、重试和重放 | [消息与事件流](docs/components/messaging.md) · RabbitMQ、RocketMQ、Kafka、Pulsar、NATS JetStream |
| 全文检索、缓存和分析 | [搜索与缓存](docs/components/search-cache.md) · Elasticsearch、Redis、Redisson、Lettuce、ClickHouse |
| 定时、分片和补跑 | [任务调度](docs/components/scheduling.md) · XXL-JOB、PowerJob、Quartz、ElasticJob、DolphinScheduler |
| 全量、增量和对账 | [SQL 数据同步与处理](docs/components/data-pipelines.md) · MySQL、PostgreSQL 逻辑复制、Canal、DTS、Flink CDC、Debezium、Kafka Connect、Apicurio Registry |
| 日志采集、解析和查询 | [分布式日志](docs/components/distributed-logging.md) · Fluent Bit、Logstash、Grafana Loki、[Kibana](docs/components/observability-tools.md#kibana) |
| 优雅上线、探针和灰度 | [发布与探针](docs/components/release-tools.md) · Kubernetes、Spring Boot Actuator、Argo Rollouts。 [全链路灰度](docs/components/traffic-routing.md) · Istio、Dubbo、Spring Cloud Gateway |
| 配置灰度、服务发现、进程协调、容错和授权 | [服务治理](docs/components/service-tools.md) · Nacos、etcd、Sentinel、Resilience4j、Seata、OPA |
| 指标、告警和链路 | [采集与指标](docs/components/service-tools.md#opentelemetry) · OpenTelemetry、Prometheus。 [查询与告警](docs/components/observability-tools.md) · Grafana、Alertmanager、Tempo |
| 打包和构建镜像 | [Dockerfile 与 Compose](docs/components/docker-build.md) · Maven Wrapper、BuildKit、镜像和容器检查 |
| 镜像仓库、部署模板和 Git 同步 | [制品与持续交付](docs/components/delivery-tools.md) · Harbor、Helm、Kustomize、kubeconform、Argo CD |

## 学习路线

[按业务场景学习](docs/learning-path.md)，从事务消息、缓存与检索、任务补跑、数据同步、服务保护或故障定位入手。每条路线都给出阅读顺序、练习步骤和要核对的结果。

## 许可证

[Apache License 2.0](LICENSE)

每轮选题理由和实测结果见[维护记录](docs/maintenance-log.md)。
