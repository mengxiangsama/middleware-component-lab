# Middleware Component Lab

[![CI](https://github.com/mengxiangsama/middleware-component-lab/actions/workflows/ci.yml/badge.svg)](https://github.com/mengxiangsama/middleware-component-lab/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

本仓库收录中间件组件手册、按业务场景组织的学习路线，以及可在本地运行的 Java 实验。手册提供常见用法、最小接入步骤、验证方法和官方文档。

## 从哪里开始

| 你想了解什么 | 入口 |
| --- | --- |
| 订单落库后怎样可靠地向其他服务发布消息 | [RocketMQ 事务消息实验](#run-locally)，启动 Broker 和示例应用，用 HTTP 请求观察提交、回滚、事务回查和消费去重。 |
| 同一服务内，订单支付后怎样给用户发积分 | [Spring Modulith 订单积分实验](#spring-modulith-订单积分实验)，用集成测试复现失败重投、重复投递和投递记录清理。 |
| 客户端怎样建立长连接并双向收发消息 | [Netty WebSocket 回显实验](labs/netty-websocket/README.md)，建立连接、核对文本回显并正常关闭。 |
| 查一个组件的接入方式 | [组件目录](docs/components/README.md) |
| 按业务场景做练习 | [学习路线](docs/learning-path.md) |

这些 Java 实验都需要 Java 17+，在仓库根目录运行自带的 Maven Wrapper。RocketMQ 事务消息的端到端演示还需要 Docker Compose，Spring Modulith 的测试使用内嵌 H2。

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
| 消息发布、重试和重放 | [消息与事件流](docs/components/messaging.md) · RabbitMQ、RocketMQ、Kafka、Pulsar、NATS JetStream |
| 全文检索、缓存和分析 | [搜索与缓存](docs/components/search-cache.md) · Elasticsearch、Redis、Redisson、Lettuce、ClickHouse |
| 定时、分片和补跑 | [任务调度](docs/components/scheduling.md) · XXL-JOB、PowerJob、Quartz、ElasticJob、DolphinScheduler |
| 全量、增量和对账 | [SQL 数据同步与处理](docs/components/data-pipelines.md) · MySQL、PostgreSQL 逻辑复制、Canal、DTS、Flink CDC、Debezium、Kafka Connect、Apicurio Registry |
| 日志采集、解析和查询 | [分布式日志](docs/components/distributed-logging.md) · Fluent Bit、Logstash、Grafana Loki、[Kibana](docs/components/observability-tools.md#kibana) |
| 优雅上线、探针和灰度 | [发布与探针](docs/components/release-tools.md) · Kubernetes、Spring Boot Actuator、Argo Rollouts。 [全链路灰度](docs/components/traffic-routing.md) · Istio、Dubbo、Spring Cloud Gateway |
| 配置灰度、服务发现和容错 | [服务治理](docs/components/service-tools.md) · Nacos、Sentinel、Resilience4j、Seata |
| 指标、告警和链路 | [采集与指标](docs/components/service-tools.md#opentelemetry) · OpenTelemetry、Prometheus。 [查询与告警](docs/components/observability-tools.md) · Grafana、Alertmanager、Tempo |
| 打包和构建镜像 | [Dockerfile 与 Compose](docs/components/docker-build.md) · Maven Wrapper、BuildKit、镜像和容器检查 |
| 镜像仓库、部署模板和 Git 同步 | [制品与持续交付](docs/components/delivery-tools.md) · Harbor、Helm、Argo CD |

## 学习路线

[按业务场景学习](docs/learning-path.md)，从事务消息、缓存与检索、任务补跑、数据同步、服务保护或故障定位入手。每条路线都给出阅读顺序、练习步骤和要核对的结果。

## 许可证

[Apache License 2.0](LICENSE)
