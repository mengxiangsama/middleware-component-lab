# Middleware Component Lab

[![CI](https://github.com/mengxiangsama/middleware-component-lab/actions/workflows/ci.yml/badge.svg)](https://github.com/mengxiangsama/middleware-component-lab/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

第三方中间件组件手册，收录组件的常见操作、接入步骤、验证方法和官方文档。Apache RocketMQ 事务消息附可运行的 Spring Boot 实验。

Reference guides for third-party middleware, plus a runnable Apache RocketMQ experiment.

## 组件入口

| 想查什么 | 分册与常见组件 |
| --- | --- |
| 消息发布、重试和重放 | [消息与事件流](docs/components/messaging.md) · RabbitMQ、RocketMQ、Kafka、Pulsar、NATS JetStream |
| 全文检索、缓存和分析 | [搜索与缓存](docs/components/search-cache.md) · Elasticsearch、Redis、Redisson、Lettuce、ClickHouse |
| 定时、分片和补跑 | [任务调度](docs/components/scheduling.md) · XXL-JOB、PowerJob、Quartz、ElasticJob、DolphinScheduler |
| 全量、增量和对账 | [SQL 数据同步与处理](docs/components/data-pipelines.md) · MySQL、PostgreSQL 逻辑复制、Canal、DTS、Flink CDC、Debezium、Kafka Connect、Apicurio Registry |
| 日志采集、解析和查询 | [分布式日志](docs/components/distributed-logging.md) · Fluent Bit、Logstash、Grafana Loki、[Kibana](docs/components/observability-tools.md#kibana) |
| 优雅上线、探针和灰度 | [发布与探针](docs/components/release-tools.md) · Kubernetes、Spring Boot Actuator、Argo Rollouts；[全链路灰度](docs/components/traffic-routing.md) · Istio、Dubbo、Spring Cloud Gateway |
| 配置灰度、服务发现和容错 | [服务治理](docs/components/service-tools.md) · Nacos、Sentinel、Resilience4j、Seata |
| 指标、告警和链路 | [采集与指标](docs/components/service-tools.md#opentelemetry) · OpenTelemetry、Prometheus。 [查询与告警](docs/components/observability-tools.md) · Grafana、Alertmanager、Tempo |
| 打包和构建镜像 | [Dockerfile 与 Compose](docs/components/docker-build.md) · Maven Wrapper、BuildKit、镜像和容器检查 |
| 镜像仓库、部署模板和 Git 同步 | [制品与持续交付](docs/components/delivery-tools.md) · Harbor、Helm、Argo CD |

完整清单见[组件目录](docs/components/README.md)，本地运行见 [RocketMQ 事务实验](#run-locally)。代码和架构重构的指导见 [design-patterns-refactor](https://github.com/mengxiangsama/design-patterns-refactor)。

## RocketMQ transaction experiment

The Spring Boot example demonstrates the Apache RocketMQ transactional-message lifecycle:

1. the producer sends a half message;
2. the broker asks the producer to execute a local transaction;
3. the producer commits, rolls back, or returns an unknown state;
4. RocketMQ checks unknown transactions later;
5. a consumer processes committed messages idempotently.

The project intentionally uses the native RocketMQ client so the transaction boundary and checkback mechanism remain visible.

### Transaction flow

```text
POST /api/orders
      |
      v
TransactionMQProducer ---> RocketMQ half message
      |                           |
      v                           | check unknown state
H2 local transaction <------------+
  orders + local_transaction
      |
      | COMMIT only
      v
RocketMQ consumer ---> consumed_event (message-id deduplication)
```

Transactional messages provide eventual consistency between the producer's local transaction and message publication. They do not make downstream consumption part of the same database transaction; consumers still need retry-safe, idempotent processing.

## Requirements

- Java 17+
- Docker with Compose

Maven does not need to be installed; the repository includes Maven Wrapper.

## Run locally

Start RocketMQ and create a topic whose message type is `Transaction`:

```bash
docker compose up -d
docker compose ps
```

Start the application:

```bash
./mvnw spring-boot:run
```

The HTTP server listens on `http://localhost:8088`.

### 1. Commit immediately

```bash
curl -sS http://localhost:8088/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"productName":"Mechanical Keyboard","amount":399.00,"outcome":"COMMIT"}'
```

### 2. Roll back

```bash
curl -sS http://localhost:8088/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"productName":"Rollback Demo","amount":1.00,"outcome":"ROLLBACK"}'
```

No order row is created and the message is never visible to the consumer.

### 3. Trigger transaction checkback

```bash
curl -sS http://localhost:8088/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"productName":"Checkback Demo","amount":9.90,"outcome":"UNKNOWN"}'
```

The local database transaction commits, but the listener deliberately returns `UNKNOW`. RocketMQ later checks `local_transaction` and commits the half message.

Use the returned `orderId` to inspect local state:

```bash
ORDER_ID=replace-with-returned-order-id
curl -sS "http://localhost:8088/api/orders/$ORDER_ID"
```

Watch transaction and consumer activity in the application console. To inspect the broker separately:

```bash
docker compose logs -f broker
```

## Test and build

```bash
./mvnw clean verify
docker build -t rocketmq-transaction-lab:local .
```

`clean verify` runs the tests and packages the JAR used by the Dockerfile. See the [Dockerfile build guide](docs/components/docker-build.md) for the build context and image checks.

需要审查 Java 项目的无用依赖、插件或 POM 声明时，参考重构库的 [Maven 检测与清理指南](https://github.com/mengxiangsama/design-patterns-refactor/blob/main/skills/design-patterns-refactor/references/maven-cleanup.md)。

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `ROCKETMQ_NAME_SERVER` | `127.0.0.1:9876` | NameServer address |
| `ROCKETMQ_TOPIC` | `order-transaction-topic` | Transaction topic |
| `ROCKETMQ_PRODUCER_GROUP` | `order-transaction-producer` | Transaction producer group |
| `ROCKETMQ_CONSUMER_GROUP` | `order-created-consumer` | Consumer group |
| `ROCKETMQ_ENABLED` | `true` | Enables RocketMQ clients |

For a real system, replace H2 with a durable database, retain transaction evidence long enough for broker checkbacks, monitor unresolved transactions, and choose a consumer idempotency key based on the business event rather than only the RocketMQ message ID.

## License

Apache License 2.0. See [LICENSE](LICENSE).
