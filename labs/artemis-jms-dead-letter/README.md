# Apache ActiveMQ Artemis + Jakarta JMS 隔离反复失败的订单消息

## 适用场景

订单消费者收到无法解析的消息。消费者回滚，Broker 可以让消息再投一次，给暂时失败一次恢复机会。连续达到投递上限后，Broker 将该条消息转到死信队列，让排查人员读取原文和订单编号。正常订单继续在业务队列消费。

| 组件 | 分工 |
| --- | --- |
| [Apache ActiveMQ Artemis](../../docs/components/messaging.md#apache-activemq-artemis) | 将消息路由到订单队列，按地址配置决定重投与死信去向。 |
| [Jakarta JMS](../../docs/components/messaging.md#jakarta-jms) | 用事务会话消费消息，成功时提交，失败时回滚。消费者可从 JMS 消息读取投递次数。 |

## 接入步骤

1. 创建业务地址和队列 `orders.created`，以及死信地址和队列 `orders.dead`。示例两组地址和队列都用 `ANYCAST`，且各组内同名。死信规则指向地址，实际读取的是绑定在该地址的队列。
2. 给业务地址设置 `dead-letter-address=orders.dead` 和 `max-delivery-attempts=2`。上限包含第一次投递，因此示例最多发生一次重投。实验将 `redelivery-delay` 设为 0。
3. 使用 `JMSContext.SESSION_TRANSACTED` 创建消费者会话。完成处理后调用 `commit()`，本次处理失败时调用 `rollback()`。通过 `JMSXDeliveryCount` 和 `JMSRedelivered` 核对是否重投。死信消费者可读取原消息正文、应用属性和 Artemis 附加的原始地址与队列。

业务处理若写入另一数据库，应用还要处理数据库事务与消息确认之间的失败，避免重投导致副作用重复。本实验验证 Broker 的投递和死信路由。

## 本地验证

需要 JDK 17+ 和可下载 Maven 依赖的网络。从仓库根目录运行。[测试代码](src/test/java/io/github/middlewarelab/artemis/OrderDeadLetterTest.java) 在 JVM 内启动真实 Artemis Broker，通过进程内 JMS 连接收发，使用临时目录和非持久队列。

```sh
./mvnw -B -f labs/artemis-jms-dead-letter/pom.xml clean verify
```

| 事件 | 检查内容 |
| --- | --- |
| 订单 `order-42` 两次消费失败 | 投递次数依次为 1 和 2，第二次标记为重投。消费者两次都回滚。 |
| 达到投递上限 | `orders.dead` 收到同一文本和 `orderId`，`_AMQ_ORIG_ADDRESS`、`_AMQ_ORIG_QUEUE` 都是 `orders.created`。 |
| 处理下一条订单 `order-43` | 正常消费者收到并提交，死信队列未出现这条订单。 |

部署时可根据失败类型调整投递间隔与上限。持久化、跨节点与应用数据库事务按实际环境验证。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、JDK 17.0.16、Apache ActiveMQ Artemis 2.44.0 和 Jakarta Messaging API 3.1.0 上运行上述命令，1 项集成测试通过。两次回滚后，死信消费者读到了订单 `order-42` 的原文、订单 ID、源地址和源队列。随后 `order-43` 在第一次投递时成功提交。

## 官方资料

- [Artemis 死信地址与重投](https://artemis.apache.org/components/artemis/documentation/latest/undelivered-messages.html) · [嵌入 Broker](https://artemis.apache.org/components/artemis/documentation/latest/embedding-artemis.html) · [Artemis 2.44.0 服务端源码包](https://repo.maven.apache.org/maven2/org/apache/activemq/artemis-server/2.44.0/artemis-server-2.44.0-sources.jar)
- [Jakarta Messaging 3.1](https://jakarta.ee/specifications/messaging/3.1/)
