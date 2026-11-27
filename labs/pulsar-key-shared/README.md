# Apache Pulsar + Java 按订单分发发货事件

## 适用场景

多个发货消费者共同处理订单状态事件。每个订单的创建、支付和发货要交给同一个消费者并按发送顺序处理，同时让不同订单分摊到多个消费者。Java 生产者把订单号设为消息键，Pulsar 的 `Key_Shared` 订阅按键分派，两个 Java 消费者逐条处理和确认。

## STICKY 固定分组接入步骤

1. 在 Java 项目中加入 Maven 依赖 `org.apache.pulsar:pulsar-client`。本例用 4.0.14，连接 Pulsar 的 `pulsar://host:port`。生产端和消费端使用同一个持久 Topic 和 `Schema.STRING`。
2. 两个消费者使用相同的 `subscriptionName("shipment-workers")` 和 `SubscriptionType.Key_Shared`。本例用 `KeySharedPolicy.stickyHashRange()` 显式分配互不重叠的哈希范围。A 使用 `0–32767`，B 使用 `32768–65535`。
3. 两个消费者就绪后，生产者调用 `newMessage().key(orderId).value(stage).send()`。本例调用 `enableBatching(false)`。要开启批处理时，按官方说明选 `BatcherBuilder.KEY_BASED`，使不同键进入各自的批次。
4. 消费者从 `getKey()` 取得订单号，处理后调用 `acknowledge(message)` 逐条确认。业务端可以按订单号检查所到达的事件序列和处理者。

## AUTO_SPLIT 消费者接手

发货进程 A 收到 `PACKED` 后退出，来不及确认。订阅里还有进程 B，需要接手未确认事件，并继续处理同一订单的新事件。两端使用同一个 `Key_Shared` 订阅，并设置 `KeySharedPolicy.autoSplitHashRange()`。Pulsar 会根据在线消费者分配键。A 断开后，Broker 可以把它持有的键交给 B。

1. 启动独立 JVM 里的 A，发送 `order-2001` 的 `PACKED`。核对 A 收到的消息 ID，保留未确认状态。
2. 在 A 仍在线时让 B 订阅，再强制结束 A 的 JVM。B 收到重投的 `PACKED`，校验订单号、事件内容和原消息 ID。
3. B 开启 `isAckReceiptEnabled(true)`，逐条确认并等 Broker 回执。随后发布同一订单的新事件 `SHIPPED`，B 收到并确认这个新消息 ID。

## 验证方法

准备 Docker、JDK 17+ 和可下载镜像及 Maven 依赖的网络。从仓库根目录运行。

```sh
sh labs/pulsar-key-shared/verify.sh
```

脚本启动临时 Pulsar 4.0.14 standalone，把 Broker 端口发布到 `127.0.0.1:16650`，结束时关闭并删除该容器。如果端口已被占用，可用 `PULSAR_LAB_PORT=16651 sh labs/pulsar-key-shared/verify.sh` 更换端口。

脚本运行两项验证。`OrderKeySharedTest` 检查 16 张订单的 48 条消息，包括固定范围路由和每笔订单的 `CREATED → PAID → SHIPPED` 顺序。`AutoSplitHandoffTest` 检查单条未确认事件的重投，以及 B 收到同键后续事件。

也可在已启动的本机 Pulsar 4.0.14 standalone 上运行两项测试。用官方本机发行包启动 Broker 需要 Java 21。 [本机启动指南](https://pulsar.apache.org/docs/4.0.x/getting-started-standalone/)

```sh
PULSAR_LAB_URL=pulsar://127.0.0.1:6650 ./mvnw -B -f labs/pulsar-key-shared/pom.xml clean verify
```

## 实测记录

2026-11-26 在 macOS 26.5 ARM64、Docker Engine 27.3.1 和 OpenJDK 17.0.16 上运行。真实 Broker 和 Java Client 均为 Pulsar 4.0.14，镜像运行在 `linux/arm64`。当时仅运行固定范围的 `OrderKeySharedTest`，默认端口 16650 和备用端口 16651 各通过一次。A 接收 5 个订单，B 接收 11 个订单。48 条消息全部收到，每个订单都按 `CREATED → PAID → SHIPPED` 交给同一个消费者。

2026-11-27 在 macOS 26.5 ARM64、OpenJDK 21.0.5 和 SHA-512 校验通过的 Pulsar 4.0.14 本机包上运行，两项测试均通过。`AUTO_SPLIT` 实验中，A 收到 `order-2001` 的 `PACKED`，消息 ID 为 `5:0:-1`。A 的 JVM 退出后，B 收到相同 ID 并完成 ACK，随后收到同键的 `SHIPPED`，新 ID 为 `5:1:-1`。这些 ID 来自本次运行。

固定分组的顺序验证覆盖单节点、单 Topic 和两个消费者稳定在线的场景。接手验证聚焦单条在途消息、进程退出后的重投和同键后续消息，业务端仍需用事件 ID 保证写入幂等。多条同键消息同时在途、负确认及 ACK 超时场景需要按业务另做验证。

## 官方资料

- [Pulsar 4.0 Docker standalone](https://pulsar.apache.org/docs/4.0.x/getting-started-docker/) · [4.0 Key_Shared 订阅](https://pulsar.apache.org/docs/4.0.x/concepts-messaging/#key_shared)
- [Java Client KeySharedPolicy](https://pulsar.apache.org/api/client/4.0.x/org/apache/pulsar/client/api/KeySharedPolicy.html) · [4.0.14 Broker 上游验证](https://github.com/apache/pulsar/blob/v4.0.14/pulsar-broker/src/test/java/org/apache/pulsar/client/api/KeySharedSubscriptionTest.java#L280)
- [AUTO_SPLIT 成员变更与按键投递](https://pulsar.apache.org/docs/4.0.x/concepts-messaging/#preserving-order-of-message-delivery-by-key) · [Java Client ConsumerBuilder](https://pulsar.apache.org/api/client/4.0.x/org/apache/pulsar/client/api/ConsumerBuilder.html)
