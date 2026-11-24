# 消息队列与事件流

本页收录消息队列与事件流组件的典型用法、接入步骤和投递边界。仓库附 RocketMQ、Kafka 和 NATS JetStream 的可运行实验。

## RabbitMQ

- **适用与代价**｜适合按路由键或广播规则分发任务和业务事件。AMQP 0-9-1 的 Exchange 根据 Binding 将消息投到 Queue。独立订阅各用一个队列，多个 worker 共享任务队列。队列类型、绑定和死信路由需要维护。需复制的工作队列可评估 quorum queue。 [队列](https://www.rabbitmq.com/docs/queues) · [Quorum Queue](https://www.rabbitmq.com/docs/quorum-queues)
- **接入用法**｜工作队列用默认 Exchange 将任务发至指定 Queue，多个 worker 消费同一 Queue。广播可为每个订阅声明独立 Queue 并绑定 fanout Exchange。按键分发时可声明 direct 或 topic Exchange，例如把 `shipper` 队列绑定到 `order.paid`。Java 客户端通过 `basicPublish` 发布，使用 `basicConsume` 接收，处理完成后调用 `basicAck`。暂时失败走有限重试，永久失败按死信策略隔离。 [工作队列示例](https://www.rabbitmq.com/tutorials/tutorial-two-java) · [Topic 路由示例](https://www.rabbitmq.com/tutorials/tutorial-five-java)
- **确认与重试**｜Publisher Confirm 反映 Broker 侧接收结果。即使无队列匹配，发布也可能收到 confirm。要求至少匹配一条路由时，应处理 `mandatory` 返回。消费侧在业务提交后手动 ACK，按错误类型设计有限重试和 Dead Letter Exchange。设置 prefetch 限制未确认消息数，并核对所用队列类型的死信投递保证。 [确认机制](https://www.rabbitmq.com/docs/confirms) · [死信交换机](https://www.rabbitmq.com/docs/dlx)
- **顺序与重放**｜队列按 FIFO 入队和出队。多消费者、优先级和重投都会影响实际处理顺序。需要顺序时核对单活消费者和应用内处理并发。普通队列 ACK 后无法作为历史日志重读，需要从历史位点重放时评估 RabbitMQ Streams 及其保留策略。 [队列顺序](https://www.rabbitmq.com/docs/queues#message-ordering) · [Streams](https://www.rabbitmq.com/docs/streams)
- **验证**｜发布到未绑定的路由键应告警。消费者提交后断线使消息重投时，同一事件只产生一次业务写入。让死信目标暂时不可用，检查原消息的去向及恢复结果。

## Apache RocketMQ

本仓库的 NameServer 和 Broker 使用 [Compose 镜像 `apache/rocketmq:5.5.0`](../../compose.yaml)，Java 客户端使用 [`org.apache.rocketmq:rocketmq-client:5.5.1`](../../pom.xml)，通过 Remoting 连接 NameServer 和 Broker。以下清单以这组版本为准。 [5.5.0 与 5.5.1 发布记录](https://github.com/apache/rocketmq/releases)

| 功能 | 教程状态 | 用法与官方资料 |
| --- | --- | --- |
| 普通消息 | 待补 | 用 `DefaultMQProducer` 发布可独立处理的事件，由消费组订阅。 [普通消息](https://rocketmq.apache.org/docs/featureBehavior/01normalmessage/) |
| 顺序消息 | 已有教程 | Remoting 客户端将同一业务键路由到同一队列，消费端在队列内串行处理。 [订单顺序消息](#订单顺序消息) |
| 延时或定时消息 | 已有教程 | 到期后触发订单支付状态检查。 [订单定时消息](#订单定时消息) |
| 事务消息 | 已有教程 | 半消息、本地事务和回查协调订单落库与消息发布。 [订单事务消息](#订单事务消息) |
| Tag 订阅过滤 | 已有教程 | 按消息 Tag 精确匹配，可订阅一个 Tag、多个 Tag 或全部消息。 [订单事件 Tag 订阅过滤](#订单事件-tag-订阅过滤) |
| SQL92 属性过滤 | 待补 | 启用 Broker 属性过滤后，可用 SQL92 表达式匹配消息属性。 [消息过滤](https://rocketmq.apache.org/docs/featureBehavior/07messagefilter/) |
| 消费重试与死信 | 待补 | 根据消费方式配置重试次数和间隔，检查重试耗尽后的死信。 [消费重试](https://rocketmq.apache.org/docs/featureBehavior/10consumerretrypolicy/) |
| 消费进度与重放 | 待补 | 在仍保留的消息范围内重置消费位点，用独立消费组验证重放。 [消费进度](https://rocketmq.apache.org/docs/featureBehavior/09consumerprogress/) |

### 订单事件 Tag 订阅过滤

**适用场景**　支付、发货和退款事件共用一个订单主题时，财务服务只需接收支付和退款事件。生产端为每条消息设置一个稳定的事件类型 Tag，Broker 按订阅表达式过滤。Tag 适合按单个分类字段精确匹配。需要组合多个业务属性时，可使用 SQL92 属性过滤。

**接入步骤**　从仓库根目录执行。使用 NameServer 和 Broker 5.5.0、Java Remoting 客户端 5.5.1。需 Java 17+、Docker Compose，以及空闲的本地端口 9876、10909、10911 和 10912。选用尚未使用的 Compose 项目名，本例用 `middleware-daily-tags`。

```bash
export ROCKETMQ_TAG_PROJECT=middleware-daily-tags
docker compose -p "$ROCKETMQ_TAG_PROJECT" up -d
docker compose -p "$ROCKETMQ_TAG_PROJECT" ps -a
```

确认 Broker 为 `healthy`，`topic-init` 以状态码 0 退出。创建普通消息主题，使用一个读队列和一个写队列，便于核对位点。重跑时生成新的主题，测试会据此生成新消费组。

```bash
export ROCKETMQ_TAG_TOPIC="order-tag-$(date +%s)"
docker compose -p "$ROCKETMQ_TAG_PROJECT" exec -T broker sh mqadmin updateTopic \
  -n namesrv:9876 -b 127.0.0.1:10911 -t "$ROCKETMQ_TAG_TOPIC" \
  -r 1 -w 1 -a +message.type=NORMAL
```

[实验代码](../../src/test/java/io/github/rocketmqtx/OrderTagFilterLabTest.java)用 `new Message(topic, tag, body)` 设置 Tag 并发送。全量对照组用 `subscribe(topic, "*")`，财务组用 `subscribe(topic, "PAID || REFUNDED")`。单个 Tag 可直接用 `subscribe(topic, "PAID")`。同一消费组内所有实例应使用一致的主题和表达式，需要不同筛选条件的服务使用独立消费组。 [RocketMQ 5.x 消息过滤与订阅一致性](https://rocketmq.apache.org/docs/featureBehavior/07messagefilter/) · [5.5.1 Remoting Tag 示例](https://raw.githubusercontent.com/apache/rocketmq/refs/tags/rocketmq-all-5.5.1/example/src/main/java/org/apache/rocketmq/example/filter/TagFilterConsumer.java) · [5.5.0 updateTopic 参数](https://raw.githubusercontent.com/apache/rocketmq/refs/tags/rocketmq-all-5.5.0/tools/src/main/java/org/apache/rocketmq/tools/command/topic/UpdateTopicSubCommand.java)

**验证方法**　创建主题应返回 `success`。在保留主题变量的终端执行测试，确认运行了 1 项测试且跳过数为 0。

```bash
./mvnw -B -Dmaven.repo.local=target/maven-repository \
  -Drocketmq.log.root=target/rocketmq-logs -Dtest=OrderTagFilterLabTest test
```

预期三条消息均返回 `SEND_OK`。全量组收到 `PAID`、`SHIPPED`、`REFUNDED`，财务组只收到 `PAID` 和 `REFUNDED`。测试对照两个消费回调的 Tag 集合，并在财务组收到两个匹配事件后继续观察 2 秒，检查是否出现额外投递。

2026-11-23 实测环境为 macOS 26.5 arm64、OpenJDK 17.0.16、Maven 3.9.11、Docker Engine 27.3.1 和 Compose 2.30.3。按上方命令使用独立项目 `middleware-daily-tags-87apx3pr`，创建主题 `order-tag-1795405482` 并执行测试。Broker 健康，`topic-init` 以状态码 0 退出，主题创建成功，1 项测试通过且无跳过。三次发送均返回 `SEND_OK`，全量组收到位点 0、1、2 的事件，财务组收到位点 0 的 `PAID` 和位点 2 的 `REFUNDED`，观察窗口内没有额外投递。

完成后用 `docker compose -p "$ROCKETMQ_TAG_PROJECT" down -v` 清理本教程专用项目的容器、网络和数据卷。

### 订单顺序消息

**适用场景**　下游订单状态投影需依次处理同一笔订单的 `CREATED → PAID → SHIPPED`，不同订单可以并行。上游先按业务顺序串行发送，同一订单的上一条收到 `SEND_OK` 后再发下一条。多实例同时发布同一订单时，需要先在上游协调发送顺序。

**接入步骤**　使用上述服务端和 Remoting 客户端版本。从仓库根目录执行，需 Java 17+、Docker Compose，以及可用的本地端口 9876、10909、10911 和 10912。以下命令创建一个新的实验主题，配置两个读队列和两个写队列。

```bash
docker compose -p middleware-daily-fifo up -d
docker compose -p middleware-daily-fifo ps -a
```

确认 Broker 为 `healthy`、`topic-init` 以状态码 0 退出，再创建主题。

```bash
export ROCKETMQ_FIFO_TOPIC="order-fifo-$(date +%s)"
docker compose -p middleware-daily-fifo exec -T broker sh mqadmin updateTopic \
  -n namesrv:9876 -b 127.0.0.1:10911 -t "$ROCKETMQ_FIFO_TOPIC" \
  -r 2 -w 2 -a +message.type=NORMAL
```

创建主题应返回 `success`。 [5.5.0 的 updateTopic 参数](https://raw.githubusercontent.com/apache/rocketmq/refs/tags/rocketmq-all-5.5.0/tools/src/main/java/org/apache/rocketmq/tools/command/topic/UpdateTopicSubCommand.java)

[实验代码](../../src/test/java/io/github/rocketmqtx/OrderFifoLabTest.java)交错发送订单 1001 与 1002 的事件。发送端用 `DefaultMQProducer.send(message, selector, orderId)`，对候选队列排序后按订单号取模。消费组从首个可用位点开始，通过 `DefaultMQPushConsumer` 的 `MessageListenerOrderly` 在回调内顺序处理同一队列的消息，处理完成才返回 `SUCCESS`。主题使用 `NORMAL`，顺序来自生产者选队列和消费者按队列串行处理。 [队列顺序用法](https://rocketmq.apache.org/docs/4.x/producer/03message2/) · [5.5.1 生产者示例](https://raw.githubusercontent.com/apache/rocketmq/refs/tags/rocketmq-all-5.5.1/example/src/main/java/org/apache/rocketmq/example/ordermessage/Producer.java) · [5.5.1 消费者示例](https://raw.githubusercontent.com/apache/rocketmq/refs/tags/rocketmq-all-5.5.1/example/src/main/java/org/apache/rocketmq/example/ordermessage/Consumer.java)

**顺序边界**　同一订单保持在同一队列时，下游按该队列的存储顺序处理。跨队列的日志可交错，扩缩队列或路由变化可能让取模重新映射。生产遇到失败或结果不明时应暂停推进同一订单并核对发送状态。消费端在回调内完成业务写入，避免把同一队列的事件异步并行处理。临时失败可返回 `SUSPEND_CURRENT_QUEUE_A_MOMENT` 让当前队列稍后重试，同队列的其他订单也可能等待。用订单 ID 与事件序号作为幂等键，处理重投。

使用 RocketMQ 5.x gRPC Java SDK `rocketmq-client-java` 时，创建 `message.type=FIFO` 的主题，并在消息上用 `setMessageGroup(orderId)` 声明消息组。该接口的顺序范围是同一消息组。当前 Compose 实验走 Remoting 队列机制。 [5.x 消息组与 FIFO 主题](https://rocketmq.apache.org/docs/featureBehavior/03fifomessage/)

**验证方法**　沿用刚设置的主题环境变量执行。重跑时先创建新主题，确保位点从 0 开始。

```bash
./mvnw -B -Dmaven.repo.local=target/maven-repository \
  -Drocketmq.log.root=target/rocketmq-logs -Dtest=OrderFifoLabTest test
```

预期 6 条发送均返回 `SEND_OK`。测试按订单检查消费回调收到的事件顺序、队列号和位点。订单 1001 在队列 1，订单 1002 在队列 0，每笔都依次收到 `CREATED`、`PAID`、`SHIPPED`，各自的位点为 0、1、2。

2026-11-20 实测环境为 macOS arm64、OpenJDK 17.0.16、Maven 3.9.11、Docker Engine 27.3.1 和 Compose 2.30.3。按上方命令启动服务、创建主题并运行测试，`topic-init` 以状态码 0 退出，6 条发送均成功，1 项测试通过。消费日志中，订单 1001 在队列 1，订单 1002 在队列 0，两笔订单的位点均为 0、1、2，对应创建、支付和发货。

实验完成后可用 `docker compose -p middleware-daily-fifo down -v` 删除本教程专用的 Compose 服务和数据卷。

### 订单定时消息

**适用场景**　订单创建后，在付款截止时间触发一次支付状态检查。消息携带订单 ID，消费者收到后重新读取订单，只有仍待支付且已到期时才执行关单。用带状态条件的原子更新处理支付与关单的竞争，并让重复投递安全地重试。投递时间用于触发检查，消费积压或重试会使实际处理变晚。

**接入步骤**　使用 NameServer 和 Broker 5.5.0 及 Java Remoting 客户端 5.5.1。从仓库根目录执行，需 Java 17+、Docker Compose，以及可用的本地端口 9876、10909、10911 和 10912。客户端和 Broker 的时钟需同步。

```bash
docker compose -p middleware-daily-delay up -d
docker compose -p middleware-daily-delay ps -a
```

确认 Broker 为 `healthy`、`topic-init` 以状态码 0 退出，再创建一个新的 `DELAY` 主题。重跑时重新执行以下命令，使用新的主题和消费组。

```bash
export ROCKETMQ_DELAY_TOPIC="order-delay-$(date +%s)"
docker compose -p middleware-daily-delay exec -T broker sh mqadmin updateTopic \
  -n namesrv:9876 -b 127.0.0.1:10911 -t "$ROCKETMQ_DELAY_TOPIC" \
  -r 1 -w 1 -a +message.type=DELAY
```

[实验代码](../../src/test/java/io/github/rocketmqtx/OrderDelayLabTest.java)用 `DefaultMQProducer` 发送订单 1001 的 `CHECK_PAYMENT_TIMEOUT`，用 `Message#setDeliverTimeMs` 写入 Unix 毫秒时间戳，目标时间取约 10 秒后的整秒。消费者使用 `DefaultMQPushConsumer` 记录实际回调时间。 [定时消息](https://rocketmq.apache.org/docs/featureBehavior/02delaymessage/) · [5.5.1 Remoting Message API](https://raw.githubusercontent.com/apache/rocketmq/refs/tags/rocketmq-all-5.5.1/common/src/main/java/org/apache/rocketmq/common/message/Message.java) · [5.5.0 updateTopic 参数](https://raw.githubusercontent.com/apache/rocketmq/refs/tags/rocketmq-all-5.5.0/tools/src/main/java/org/apache/rocketmq/tools/command/topic/UpdateTopicSubCommand.java)

Broker 5.5.0 默认开启时间轮，粒度为 1 秒，最多可定时到 Broker 当前时间之后 3 天，超过上限会拒绝发送。已过去的时间戳会走即时投递。旧式的 `setDelayTimeLevel(3)` 按 Broker 的延时等级配置指定相对延时，默认是 10 秒。每条消息只选一种方式，Broker 在设置了延时等级时会清除定时时间戳。 [5.5.0 Broker 定时处理](https://raw.githubusercontent.com/apache/rocketmq/refs/tags/rocketmq-all-5.5.0/broker/src/main/java/org/apache/rocketmq/broker/util/HookUtils.java) · [5.5.0 Broker 存储配置](https://raw.githubusercontent.com/apache/rocketmq/refs/tags/rocketmq-all-5.5.0/store/src/main/java/org/apache/rocketmq/store/config/MessageStoreConfig.java) · [延时等级](https://rocketmq.apache.org/docs/4.x/producer/04message3/)

**验证方法**　创建主题应返回 `success`。在设置了主题变量的同一终端运行测试。

```bash
./mvnw -B -Dmaven.repo.local=target/maven-repository \
  -Drocketmq.log.root=target/rocketmq-logs -Dtest=OrderDelayLabTest test
```

预期发送在目标时间之前返回 `SEND_OK`，消费回调在目标时间到达后才收到订单 1001 的检查事件。测试在发送成功后最多等待 45 秒，断言订单 ID、事件内容，以及目标主题的 Broker 存储时间和消费回调时间均已达到计划时间。即使消费组启动偏慢，Broker 存储时间也能检出消息提前写入目标主题的情况。本实验验证消息触发，业务关单由消费应用实现。

2026-11-20 实测环境为 macOS 26.5 arm64、OpenJDK 17.0.16、Maven 3.9.11、Docker Engine 27.3.1 和 Compose 2.30.3。按上方命令启动服务、创建主题并运行测试，`topic-init` 以状态码 0 退出，主题创建成功，1 项测试通过。日志中的时间均为 UTC，发送于 `10:12:17.606721Z` 返回 `SEND_OK`，计划时间为 `10:12:27Z`。Broker 于 `10:12:27.116Z` 写入目标主题，消费者于 `10:12:27.139543Z` 收到订单 1001 的检查事件。

实验完成后可用 `docker compose -p middleware-daily-delay down -v` 删除本教程专用的 Compose 服务和数据卷。

### 订单事务消息

需要在本地订单事务提交后可靠地发布事件时，使用 `TransactionMQProducer.sendMessageInTransaction` 先发半消息，再由事务监听器执行本地事务。结果未知时由 Broker 回查，消费端只看到提交后的消息。回查应读取可跨重启查询的事务结果，消费端仍需独立处理重试和幂等。 [Remoting 事务监听器](https://rocketmq.apache.org/docs/4.x/producer/06message5/) · [事务回查与限制](https://rocketmq.apache.org/docs/featureBehavior/04transactionmessage/)

使用上述版本，按 [仓库事务消息实验](../../README.md#run-locally) 启动 Java 应用和 Compose，分别发送 `COMMIT`、`ROLLBACK`、`UNKNOWN` 请求。用订单 ID 关联 HTTP 查询和日志，核对已提交订单被消费、回滚订单查询返回 404、未知结果经回查确认提交后再消费。

## Apache Kafka

- **适用与代价**｜适合多个独立消费者需要长时间读取同一事件流，或需要处理积压和历史回放的场景。Topic 由多个分区组成，各 Consumer Group 独立保存进度，常规消费组以分区分配并发。分区规划、热点键、重平衡和保留存储需要评估。 [Kafka 设计](https://kafka.apache.org/43/design/design/)
- **接入用法**｜创建带分区和保留配置的 Topic，例如 `orders`。生产端用 `KafkaProducer.send` 发送带键的 `ProducerRecord` 并检查异步返回。常规消费组用 `subscribe` 和 `poll` 拉取，采用手动提交方案时关闭自动提交，在处理持久化后提交下一条待读位点。历史回放可用独立消费组或从指定分区位点读取。 [快速开始](https://kafka.apache.org/quickstart/) · [Producer API](https://kafka.apache.org/43/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html) · [Consumer API](https://kafka.apache.org/43/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html)
- **顺序与重放**｜日志内顺序以分区为边界。用稳定的业务键和分区策略映射同一实体，扩分区时验证映射变化。消费后数据仍受保留策略约束。启用日志压缩（log compaction）的 Topic 可能只保留某个键的较新记录，需检查能否满足全量历史重建。 [分区、消费者进度与日志压缩](https://kafka.apache.org/43/design/design/)
- **确认与重试**｜写入端核对 `acks`、复制与幂等配置。消费写外部库时，在业务提交后推进已连续处理的位点。提交间隙出现崩溃会重复处理。毒消息可由应用或框架按规则转入隔离 Topic，此举可能让后续消息先完成。Kafka 事务可原子提交 Kafka 输出与输入位点，下游以 `read_committed` 读取时可跳过已中止事务。外部写入需另有幂等或原子协调。 [投递语义和事务](https://kafka.apache.org/43/design/design/#message-delivery-semantics)
- **验证**｜消费写库后、提交位点前终止进程，重平衡后检查重复写入。用同一业务键测试扩分区和重试 Topic 对顺序的影响。在保留窗口内以独立消费组重建读模型并对账。

### 发货事件的位点恢复与独立重放

消费者处理发货事件后提交下一条记录的 offset。同组消费者重启时使用已提交的位点继续读取，独立重建消费者用 `assign` 和 `seek` 定点读取。运行 [Kafka Java Client + KRaft 实验](../../labs/kafka-offset-replay/README.md)，核对正常恢复和历史重放的订单号，以及原消费组位点是否变化。

## Apache Pulsar

- **适用与代价**｜适合同时需要多租户、多个独立订阅及按订阅分摊工作。以 Tenant 和 Namespace 组织 Topic 与策略，Subscription 记录独立消费进度。Broker 与 BookKeeper 分开扩容，部署和故障定位涉及更多组件。 [架构](https://pulsar.apache.org/docs/concepts-architecture-overview/) · [消息与订阅](https://pulsar.apache.org/docs/concepts-messaging/)
- **接入用法**｜以常规持久 Topic 和 v4 Java API 为例，指定 `persistent://tenant/namespace/orders` 和 Schema 创建 producer，设置消息键并 `send`。使用 `subscriptionName` 和订阅类型创建 consumer，`receive` 后成功调用 `acknowledge`，可重试失败调用 `negativeAcknowledge` 或按策略延迟重投。只读历史可使用 `Reader` 指定起始 Message ID，读取范围取决于实际保留数据。 [Java 客户端示例](https://pulsar.apache.org/docs/client-libraries/java-use/) · [Java API 选型](https://pulsar.apache.org/docs/client-libraries/java/)
- **顺序与确认**｜Shared 分散任务，Key_Shared 将相同键的消息路由给相应消费者。选用 Key_Shared 时关闭普通批处理或使用按键批处理，Shared 和 Key_Shared 采用单条确认。按订阅类型核对 nack、ACK 超时、重投和死信支持。失败重投可能改变处理顺序。 [订阅与重投](https://pulsar.apache.org/docs/concepts-messaging/)
- **保留与事务**｜要重放已确认数据，应配置保留并验证订阅位点能否回退到需要的历史。未确认消息的过期配置与已确认消息的保留策略分别管理。Pulsar 事务可协调自身的写入和确认，调用外部系统时需设计应用级恢复。 [保留与过期](https://pulsar.apache.org/docs/concepts-messaging/#message-retention-and-expiry) · [事务](https://pulsar.apache.org/docs/txn-why/)
- **验证**｜以同一业务键验证 consumer 增减、批处理及 nack 后的处理结果。确认过期消息如何影响订阅积压，以及延迟发现故障后是否还有足够历史恢复。

## NATS JetStream

- **适用与代价**｜适合已有 NATS Subject 路由、还需要持久化消息和按需重放的服务。Stream 接收匹配 Subject 的消息，具有状态的 Consumer 保存消费进度。不同业务使用各自的 Consumer，处理相同任务的一组 worker 可共享 durable pull consumer。需要给 Stream 设置保留、存储与复制边界。 [JetStream 概念](https://docs.nats.io/concepts/jetstream) · [Pull Consumer](https://docs.nats.io/learn/jetstream/pull-consumers)
- **接入用法**｜启用 JetStream，创建匹配 `orders.*` 的 Stream，用 JetStream 客户端向 `orders.paid` 发布并检查 PubAck。创建持久 pull consumer，按需拉取，成功显式 ACK，短暂失败可 NAK 并延迟重投。回放时创建有指定起点的 consumer，并确认保留策略仍包含所需记录。 [第一个 Stream](https://docs.nats.io/learn/jetstream/your-first-stream) · [Pull Consumer](https://docs.nats.io/learn/jetstream/pull-consumers)
- **确认与重投**｜PubAck 表示服务端已把消息存入 Stream，持久化程度由 Stream 存储与复制设置决定。用 `AckWait` 和最大投递次数管理投递预算，到达上限后可根据 advisory 追踪失败记录。发布去重取决于消息 ID 和窗口，消费业务副作用另行验证。 [发布](https://docs.nats.io/learn/jetstream/publishing) · [确认与重投](https://docs.nats.io/learn/jetstream/acknowledgment)
- **顺序与重放**｜Stream 序列可用于顺序读取，多个 worker 并行完成和超时重投会改变业务处理顺序。按保留策略确定可回放范围。Limits 受时间、消息数和大小等上限约束，WorkQueue 在确认后删除已处理消息。 [顺序读取](https://docs.nats.io/learn/jetstream/ordered-consumer) · [保留策略](https://docs.nats.io/learn/jetstream/retention-policies)
- **验证**｜模拟 worker 卡顿超过 ACK 时限及迟到 ACK，检查重复执行与停机后的进度恢复。让固定错误的消息达到投递上限，确认能追踪并修复。用实际保留配置验证可重放窗口。

### 搭配 SQLite 防止重投导致积分重复入账

订单支付事件可能在积分事务已提交、ACK 未发出时被重投。用 SQLite 主键约束去重，在同一事务登记事件 ID 和更新积分，再向 JetStream 确认。[运行联调实验](../../labs/nats-jetstream-sqlite/README.md)，核对两次投递的 Stream 序号和最终积分余额。
