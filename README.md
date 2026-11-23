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
| 同一服务内，订单支付后怎样给用户发积分 | [Spring Modulith 订单积分实验](#spring-modulith-订单积分实验)，用集成测试复现失败重投、重复投递和投递记录清理。 |
| 客户端怎样建立长连接并双向收发消息 | [Netty WebSocket 回显实验](labs/netty-websocket/README.md)，建立连接、核对文本回显并正常关闭。 |
| 告警上线前怎样验证等待、触发与恢复 | [Prometheus 告警规则实验](labs/prometheus-rules/README.md)，用固定样本检查短暂故障、持续故障和等待时间重置。 |
| 发布前怎样发现 Helm 环境参数填错 | [Helm 参数校验实验](labs/helm-values-schema/README.md)，用 `values.schema.json` 检查端口类型、取值范围和键名拼写。 |
| 新版应用如何在启动时升级旧订单表 | [Spring Boot + Flyway](labs/flyway/README.md)，验证自动迁移、重启和脚本校验失败时的启动行为。 |
| 怎样检查应用健康和 JVM 指标 | [Actuator 健康探针](#actuator-健康探针) · [应用信息和指标](#应用信息和指标) |
| 商品改价后怎样避免继续读到本机旧缓存 | [Spring Cache + Caffeine](labs/spring-cache-caffeine/README.md)，验证提交后单键失效与事务回滚。 |
| 订单报错后怎样集中检索完整 Java 堆栈 | [Fluent Bit + Loki](labs/fluent-bit-java-multiline/README.md#搭配-loki-集中检索完整堆栈)，从文件采集到按服务查异常，也可以[单独验证多行合并](labs/fluent-bit-java-multiline/README.md#验证方法)。 |

这些 Java 实验都需要 Java 17+，在仓库根目录运行自带的 Maven Wrapper。RocketMQ 事务消息的端到端演示还需要 Docker Compose，Spring Modulith 的测试使用内嵌 H2。Prometheus 规则实验只需 Docker 和 Shell，运行 `sh labs/prometheus-rules/verify.sh`。

Helm 参数校验实验需要 Helm 4.3.2 和 Shell。在仓库根目录运行 `sh labs/helm-values-schema/verify.sh`，无需 Kubernetes 集群。

在仓库根目录运行 `./mvnw -B -f labs/flyway/pom.xml -Dtest=OrderStartupMigrationTest test` 可实测 Spring Boot 启动迁移，使用内嵌 H2。

运行 `./mvnw -B -f labs/spring-cache-caffeine/pom.xml test` 可联调商品缓存与数据库事务，使用内嵌 H2。

在仓库根目录运行 `sh labs/fluent-bit-java-multiline/verify.sh` 可验证多行采集，需要 Docker、Shell 和 `jq`。运行 `sh labs/fluent-bit-java-multiline/verify-loki.sh` 可联调 Loki 写入与查询，另需 Docker Compose 和 `curl`。

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
| 配置灰度、服务发现和容错 | [服务治理](docs/components/service-tools.md) · Nacos、Sentinel、Resilience4j、Seata |
| 指标、告警和链路 | [采集与指标](docs/components/service-tools.md#opentelemetry) · OpenTelemetry、Prometheus。 [查询与告警](docs/components/observability-tools.md) · Grafana、Alertmanager、Tempo |
| 打包和构建镜像 | [Dockerfile 与 Compose](docs/components/docker-build.md) · Maven Wrapper、BuildKit、镜像和容器检查 |
| 镜像仓库、部署模板和 Git 同步 | [制品与持续交付](docs/components/delivery-tools.md) · Harbor、Helm、Argo CD |

## 学习路线

[按业务场景学习](docs/learning-path.md)，从事务消息、缓存与检索、任务补跑、数据同步、服务保护或故障定位入手。每条路线都给出阅读顺序、练习步骤和要核对的结果。

## 许可证

[Apache License 2.0](LICENSE)

每轮选题理由和实测结果见[维护记录](docs/maintenance-log.md)。
