# Apache Pulsar + Java 按订单分发发货事件

## 适用场景

多个发货消费者共同处理订单状态事件。每个订单的创建、支付和发货要交给同一个消费者并按发送顺序处理，同时让不同订单分摊到多个消费者。Java 生产者把订单号设为消息键，Pulsar 的 `Key_Shared` 订阅按键分派，两个 Java 消费者逐条处理和确认。

## 接入步骤

1. 在 Java 项目中加入 Maven 依赖 `org.apache.pulsar:pulsar-client`。本例用 4.0.14，连接 Pulsar 的 `pulsar://host:port`。生产端和消费端使用同一个持久 Topic 和 `Schema.STRING`。
2. 两个消费者使用相同的 `subscriptionName("shipment-workers")` 和 `SubscriptionType.Key_Shared`。本例用 `KeySharedPolicy.stickyHashRange()` 显式分配互不重叠的哈希范围。A 使用 `0–32767`，B 使用 `32768–65535`。
3. 两个消费者就绪后，生产者调用 `newMessage().key(orderId).value(stage).send()`。本例调用 `enableBatching(false)`。要开启批处理时，按官方说明选 `BatcherBuilder.KEY_BASED`，使不同键进入各自的批次。
4. 消费者从 `getKey()` 取得订单号，处理后调用 `acknowledge(message)` 逐条确认。业务端可以按订单号检查所到达的事件序列和处理者。

## 验证方法

准备 Docker、JDK 17+ 和可下载镜像及 Maven 依赖的网络。从仓库根目录运行。

```sh
sh labs/pulsar-key-shared/verify.sh
```

脚本启动临时 Pulsar 4.0.14 standalone，把 Broker 端口发布到 `127.0.0.1:16650`，结束时关闭并删除该容器。如果端口已被占用，可用 `PULSAR_LAB_PORT=16651 sh labs/pulsar-key-shared/verify.sh` 更换端口。

生产者先为全部 16 张订单发送 `CREATED`，再发送 `PAID` 和 `SHIPPED`，共 48 条。测试检查每个订单一直由一个消费者接收，且三个状态顺序正确。另核对已知路由样本 `order-1002` 由 A 接收，`order-1001` 由 B 接收。两个消费者使用同一个订阅，处理结果按订单号核对。

## 实测记录

2026-11-26 在 macOS 26.5 ARM64、Docker Engine 27.3.1 和 OpenJDK 17.0.16 上运行。真实 Broker 和 Java Client 均为 Pulsar 4.0.14，镜像运行在 `linux/arm64`。默认端口 16650 和备用端口 16651 各运行一次，两次都通过。A 接收 5 个订单，B 接收 11 个订单。48 条消息全部收到，每个订单都按 `CREATED → PAID → SHIPPED` 交给同一个消费者。

本轮验证范围为单节点、单 Topic、两个消费者稳定在线时的固定哈希范围分派、接收顺序和逐条 ACK 调用。消费者增减和失败重投可结合实际部署另行演练。

## 官方资料

- [Pulsar 4.0 Docker standalone](https://pulsar.apache.org/docs/4.0.x/getting-started-docker/) · [4.0 Key_Shared 订阅](https://pulsar.apache.org/docs/4.0.x/concepts-messaging/#key_shared)
- [Java Client KeySharedPolicy](https://pulsar.apache.org/api/client/4.0.x/org/apache/pulsar/client/api/KeySharedPolicy.html) · [4.0.14 Broker 上游验证](https://github.com/apache/pulsar/blob/v4.0.14/pulsar-broker/src/test/java/org/apache/pulsar/client/api/KeySharedSubscriptionTest.java#L280)
