# Kafka 消费位点恢复与独立重放

## 适用场景

发货服务把事件写入 Kafka，履约看板收到事件后更新订单状态。消费者重启时需要从上次提交的位置继续。看板规则变更、需要从历史事件重建时，可以用独立消费者定点读取，并保留线上消费组的位点。

| 组件 | 分工 |
| --- | --- |
| Kafka KRaft Broker | 在分区内保存发货事件，并记录各消费组的提交进度。 |
| Kafka Java Client | 消费者处理记录成功后手动提交。重建消费者用独立的 group ID，显式指定分区和重放起点。 |

## 接入步骤

1. 生产者向 `orders.shipped` 写入消息，以订单号作为 key。本实验显式发到分区 0，便于对照发货记录和 offset。多分区时，同一 key 的路由取决于采用的分区器和分区数。
2. 业务消费者用 `group.id=shipping-live` 订阅 topic，并关闭 `enable.auto.commit`。处理成功后按分区提交下一条待消费记录的 offset。实验处理 offset 0 后提交 `1`，同组的新消费者从 offset 1 收到第二单。
3. 重建消费者使用 `group.id=shipping-rebuild`，通过 `assign` 指定分区并用 `seek` 指定起点。本地读取位置设为 0 后，核对两条发货记录，再查询 `shipping-live` 的提交位点确认仍为 1。

`position` 随 `poll` 拉取记录推进，用来定位本次会话的下次读取。已提交的位点用于同消费组重新加入后的恢复。`seek` 改变当前消费者的读取位置。是否更新消费组进度由提交操作决定。

生产补数时先检查分区最早可读位点和保留区间，再确定重放范围。处理后提交可能遇到业务已成功、提交未完成的窗口，重复写入应由业务幂等键处理。

## 本地验证

准备 JDK 17 与可下载 Maven 依赖的网络。在仓库根目录执行。

```sh
./mvnw -B -f labs/kafka-offset-replay/pom.xml clean verify
```

测试从 Apache Kafka 4.1.2 Maven Broker 依赖启动真实单节点 KRaft，自动建分区、写入消息和检查提交位点。临时数据和回环端口由测试管理，无需 Docker、ZooKeeper 或外部集群。测试验证消息与 offset，履约看板写入、消费多分区和故障转移需在实际环境另测。

| 步骤 | 预期结果 |
| --- | --- |
| 发布两条发货事件 | 订单 101 和 102 依次写入分区 0 的 offset 0 和 1。 |
| 正常消费并提交 | 读取订单 101，`shipping-live` 提交 offset 1。 |
| 同组重新订阅 | 从 offset 1 收到订单 102。 |
| 独立重放 | 收到订单 101 和 102，业务组提交位点仍为 1。 |

## 实测记录

2026-11-24 在 macOS ARM64、JDK 17.0.16 上运行 Maven `clean verify`，Kafka 4.1.2 Broker 和 Java Client 联调通过，1 个测试通过。测试确认订单 101 和 102 的 offset 分别为 0 和 1，重建消费者读回两条事件，`shipping-live` 在重放后仍提交于 offset 1。

## 官方资料

- [Kafka 4.1.2 发布](https://github.com/apache/kafka/releases/tag/4.1.2) · [KRaft Quickstart](https://kafka.apache.org/41/getting-started/quickstart/)
- [KafkaConsumer 的读取位置、提交和定位](https://kafka.apache.org/41/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html) · [Kafka 消费者配置](https://kafka.apache.org/41/configuration/consumer-configs/)
