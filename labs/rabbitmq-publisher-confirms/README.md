# RabbitMQ + Java 验证发布确认与无路由退回

## 适用场景

订单服务发布 `orders.paid` 事件。队列绑定正常时，消息应该抵达消费队列。routing key 配错时，Broker 仍可能给发布者发 ACK，因此应用还要处理无路由退回，才能发现本次发布没有匹配队列。

| 组件 | 分工 |
| --- | --- |
| [RabbitMQ](../../docs/components/messaging.md#rabbitmq) | 将 direct exchange 的 routing key 与队列绑定比对，并向发布连接确认 Broker 已处理本次发布。 |
| RabbitMQ Java Client | 启用 publisher confirms，发送设置了 `mandatory` 的消息，并接收带消息 ID、routing key 和正文的退回事件。 |

## 接入步骤

1. 由应用声明 direct exchange，绑定订单队列和 routing key `orders.paid`。实验为交换机和队列使用临时资源。
2. 在发布 Channel 上调用 `confirmSelect()`，并在发布前调用 `addReturnListener()` 注册退回监听。通过 `basicPublish(exchange, key, true, properties, body)` 发送，第三个参数把 `mandatory` 设为 `true`。
3. 监听器用消息属性的 `messageId` 对应原始发布，并保留 Broker 返回的 routing key。实验每次只发一条，使用 `waitForConfirmsOrDie(timeout)` 等待确认。并发发布时可以用发布序号关联 confirm，用消息 ID 关联退回事件。
4. 当 `mandatory` 消息无法路由时，RabbitMQ 在 confirm 前发 `basic.return`。处理到 `312 NO_ROUTE` 时，通过返回的交换机和 routing key 排查绑定。消费处理完成由消费者另外向 Broker 确认。

## 本地验证

需要 JDK 17+、本机 Docker 和 OpenSSL。从仓库根目录运行。

```sh
sh labs/rabbitmq-publisher-confirms/verify.sh
```

脚本启动 RabbitMQ 4.2.5 官方容器，生成临时凭据，将 AMQP 端口绑定到本机回环地址。运行 [Java 集成测试](src/test/java/io/github/middlewarelab/rabbitmq/PublisherConfirmTest.java)，退出时清理容器和匿名数据卷。

| 发布 | 核对结果 |
| --- | --- |
| `orders.paid`，有匹配绑定 | 发布 Channel 收到 ACK，退回队列为空，从订单队列读到 `paid-42` 及其原始正文。 |
| `orders.unknown`，没有绑定 | 发布 Channel 同样收到 ACK。Java 退回监听收到 `312 NO_ROUTE`、原 exchange、routing key、`paid-43` 及其原始正文。订单队列没有第二条消息。 |

本实验覆盖一个 Broker 上两次顺序发布的确认和路由结果。持久消息、Broker 重启、多副本以及订单业务处理需要按实际部署另行验证。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、Docker Engine 27.3.1、JDK 17.0.16、RabbitMQ 4.2.5 和 Java Client 5.28.0 上运行上述脚本，1 项集成测试通过。两次发布均获 Broker ACK。有路由的消息可从队列读出。无路由的消息由 `basic.return` 送回，退回码为 `312 NO_ROUTE`，消息 ID 和正文均与发布内容一致。

## 官方资料

- [RabbitMQ 4.2 发布确认与无路由行为](https://www.rabbitmq.com/docs/4.2/confirms) · [Java Client API](https://www.rabbitmq.com/client-libraries/java-api-guide) · [Java 发布确认示例](https://www.rabbitmq.com/tutorials/tutorial-seven-java)
- [RabbitMQ 4.2.5 发布](https://github.com/rabbitmq/rabbitmq-server/releases/tag/v4.2.5) · [Java Client 5.28.0 发布](https://github.com/rabbitmq/rabbitmq-java-client/releases/tag/v5.28.0)
