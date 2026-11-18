# 消息队列与事件流

本页收录消息队列与事件流组件的典型用法、接入步骤和投递边界。RocketMQ 附仓库内可运行实验，其余组件可从对应官方快速开始验证。

## RabbitMQ

- **适用与代价**｜适合按路由键或广播规则分发任务和业务事件。AMQP 0-9-1 的 Exchange 根据 Binding 将消息投到 Queue。独立订阅各用一个队列，多个 worker 共享任务队列。队列类型、绑定和死信路由需要维护。需复制的工作队列可评估 quorum queue。 [队列](https://www.rabbitmq.com/docs/queues) · [Quorum Queue](https://www.rabbitmq.com/docs/quorum-queues)
- **接入用法**｜工作队列用默认 Exchange 将任务发至指定 Queue，多个 worker 消费同一 Queue。广播可为每个订阅声明独立 Queue 并绑定 fanout Exchange。按键分发时可声明 direct 或 topic Exchange，例如把 `shipper` 队列绑定到 `order.paid`。Java 客户端通过 `basicPublish` 发布，使用 `basicConsume` 接收，处理完成后调用 `basicAck`。暂时失败走有限重试，永久失败按死信策略隔离。 [工作队列示例](https://www.rabbitmq.com/tutorials/tutorial-two-java) · [Topic 路由示例](https://www.rabbitmq.com/tutorials/tutorial-five-java)
- **确认与重试**｜Publisher Confirm 反映 Broker 侧接收结果。即使无队列匹配，发布也可能收到 confirm。要求至少匹配一条路由时，应处理 `mandatory` 返回。消费侧在业务提交后手动 ACK，按错误类型设计有限重试和 Dead Letter Exchange。设置 prefetch 限制未确认消息数，并核对所用队列类型的死信投递保证。 [确认机制](https://www.rabbitmq.com/docs/confirms) · [死信交换机](https://www.rabbitmq.com/docs/dlx)
- **顺序与重放**｜队列按 FIFO 入队和出队。多消费者、优先级和重投都会影响实际处理顺序。需要顺序时核对单活消费者和应用内处理并发。普通队列 ACK 后无法作为历史日志重读，需要从历史位点重放时评估 RabbitMQ Streams 及其保留策略。 [队列顺序](https://www.rabbitmq.com/docs/queues#message-ordering) · [Streams](https://www.rabbitmq.com/docs/streams)
- **验证**｜发布到未绑定的路由键应告警。消费者提交后断线使消息重投时，同一事件只产生一次业务写入。让死信目标暂时不可用，检查原消息的去向及恢复结果。

## Apache RocketMQ

- **适用与代价**｜适合需要事务消息回查、延迟投递或按业务键处理顺序消息的服务。事务消息需要可持久查询的本地事务结果，并承担未决事务和超时恢复的成本。 [事务消息](https://rocketmq.apache.org/docs/featureBehavior/04transactionmessage/) · [顺序消息](https://rocketmq.apache.org/docs/featureBehavior/03fifomessage/)
- **接入用法**｜按 Broker 和 SDK 版本创建匹配消息类型的 Topic。普通消息由 producer 发送，consumer 以消费组订阅 Topic，可按 Tag 过滤。延迟消息用 SDK 支持的参数设定可投递时间。FIFO 消息按 SDK 指定消息组或选择队列。事务消息可按本仓库的 Java 客户端示例注册事务监听器，通过 `sendMessageInTransaction` 发送，由监听器执行本地事务并实现回查。 [快速开始](https://rocketmq.apache.org/docs/quickStart/01quickstart/) · [延迟消息](https://rocketmq.apache.org/docs/featureBehavior/02delaymessage/) · [事务消息实验](../../README.md#run-locally)
- **确认与重试**｜事务消息经半消息、本地事务、提交或回滚。结果未知时由 Broker 回查，消费端仅看到提交的消息。回查应读取本地已提交的事务证据，其保留期要覆盖回查与恢复窗口。消费确认是单独的边界，按客户端类型检查重试条件、间隔和死信策略。 [事务回查与限制](https://rocketmq.apache.org/docs/featureBehavior/04transactionmessage/) · [消费重试](https://rocketmq.apache.org/docs/featureBehavior/10consumerretrypolicy/)
- **顺序与重放**｜顺序消息需按业务键和对应客户端的顺序机制设计，检查发送并发、消费并发与失败重试。事务消息类型与 FIFO 消息类型需要分别核对。消费位置可在仍保留、可见的消息上调整或重放，先确认保留窗口、积压和下游写入容量。 [顺序消息](https://rocketmq.apache.org/docs/featureBehavior/03fifomessage/) · [消费进度](https://rocketmq.apache.org/docs/featureBehavior/09consumerprogress/) · [消息存储](https://rocketmq.apache.org/docs/featureBehavior/11messagestorepolicy/)
- **实验与验证**｜仓库现有实验覆盖 COMMIT、ROLLBACK、UNKNOWN 回查与消费去重。应用到业务时测试生产者在本地提交后、Broker 得知结果前退出，以及消费提交后重投。真实系统应使用可跨重启查询的事务记录和业务事件 ID。 [运行事务消息实验](../../README.md#run-locally)

## Apache Kafka

- **适用与代价**｜适合多个独立消费者需要长时间读取同一事件流，或需要处理积压和历史回放的场景。Topic 由多个分区组成，各 Consumer Group 独立保存进度，常规消费组以分区分配并发。分区规划、热点键、重平衡和保留存储需要评估。 [Kafka 设计](https://kafka.apache.org/43/design/design/)
- **接入用法**｜创建带分区和保留配置的 Topic，例如 `orders`。生产端用 `KafkaProducer.send` 发送带键的 `ProducerRecord` 并检查异步返回。常规消费组用 `subscribe` 和 `poll` 拉取，采用手动提交方案时关闭自动提交，在处理持久化后提交下一条待读位点。历史回放可用独立消费组或从指定分区位点读取。 [快速开始](https://kafka.apache.org/quickstart/) · [Producer API](https://kafka.apache.org/43/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html) · [Consumer API](https://kafka.apache.org/43/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html)
- **顺序与重放**｜日志内顺序以分区为边界。用稳定的业务键和分区策略映射同一实体，扩分区时验证映射变化。消费后数据仍受保留策略约束。启用日志压缩（log compaction）的 Topic 可能只保留某个键的较新记录，需检查能否满足全量历史重建。 [分区、消费者进度与日志压缩](https://kafka.apache.org/43/design/design/)
- **确认与重试**｜写入端核对 `acks`、复制与幂等配置。消费写外部库时，在业务提交后推进已连续处理的位点。提交间隙出现崩溃会重复处理。毒消息可由应用或框架按规则转入隔离 Topic，此举可能让后续消息先完成。Kafka 事务可原子提交 Kafka 输出与输入位点，下游以 `read_committed` 读取时可跳过已中止事务。外部写入需另有幂等或原子协调。 [投递语义和事务](https://kafka.apache.org/43/design/design/#message-delivery-semantics)
- **验证**｜消费写库后、提交位点前终止进程，重平衡后检查重复写入。用同一业务键测试扩分区和重试 Topic 对顺序的影响。在保留窗口内以独立消费组重建读模型并对账。

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
