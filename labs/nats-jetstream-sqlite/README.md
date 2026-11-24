# NATS JetStream + SQLite 防止订单积分重复入账

## 适用场景

订单支付后，积分服务消费支付事件，为会员加积分。Worker 可能在提交积分事务后、发送 ACK 前断开连接。JetStream 会重投未确认的消息。积分服务用事件 ID 去重，同一支付事件重投后仍只加一次积分。

| 组件 | 分工 |
| --- | --- |
| [NATS JetStream](../../docs/components/messaging.md#nats-jetstream) | 持久保存 `orders.paid` 消息，为 durable pull consumer 保存消费状态，并在等待 ACK 超时后重新投递。 |
| SQLite | 保存已处理的事件和会员余额。同一数据库事务内登记事件和更新积分，使这两步一起提交或回滚。 |

## 接入步骤

1. 启用 JetStream，创建订阅 `orders.paid` 的 Stream，发布 JSON 事件，检查发布确认。每条事件携带生产方生成的稳定事件 ID。实验使用 `ep-1001`，给会员 42 加 100 积分。
2. 创建名为 `rewards` 的 durable pull consumer，启用逐条显式确认（`AckExplicitPolicy`），设置 `AckWait`。两个 Worker 分别连入 JetStream，按需从同一个 durable consumer 拉取消息。
3. Worker 打开 SQLite 事务，向 `processed_events` 插入事件 ID。以 `event_id` 为主键，使用 `ON CONFLICT(event_id) DO NOTHING`。插入一行时才更新 `balances`。若插入零行，表示该事件已经处理，余额保持原值。
4. 确认数据库事务成功后调用 `DoubleAck(ctx)`，等待服务端确认收到并处理 ACK。数据库提交与消息确认是两个操作，夹在两者之间的断连仍可能触发重投，因此重复投递也走同一事务逻辑。

## 本地验证

准备 Go 1.24+、支持 CGO 的 C 编译器和可下载 Go 依赖的网络。实验使用 NATS Server 2.12.2、nats.go 1.47.0 和 go-sqlite3 1.14.33。从仓库根目录运行下面的命令。

```sh
(cd labs/nats-jetstream-sqlite && CGO_ENABLED=1 go run .)
```

程序在本机回环地址启动内嵌 NATS Server，并使用临时目录存放 JetStream 数据和 SQLite 数据库。预期结果如下。

| 阶段 | 验证结果 |
| --- | --- |
| Worker A 首次拉取 | `NumDelivered=1`。提交积分事务后断开 NATS 连接，待确认消息由 JetStream 保留。 |
| Worker B 拉取同一 durable consumer | `AckWait` 超时后收到相同 Stream 序号，`NumDelivered=2`。SQLite 检测到相同事件 ID，余额仍为 100。 |
| Worker B 确认完成 | `DoubleAck` 成功后，`NumAckPending=0`，库中只有一条事件处理记录。 |

实验把 `AckWait` 设为 1 秒以便观察。接入真实服务时，根据处理耗时设置确认窗口和重投策略。本实验覆盖单节点上的 Worker 断连与重投。Broker 重启、集群故障转移和跨服务副作用需要在各自的部署环境验证。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64 上，使用 Go 1.24.2、NATS Server 2.12.2、nats.go 1.47.0 和 SQLite 3.51.1 运行上述命令。第一次和第二次投递的 Stream 序号均为 `1`，投递次数分别为 `1` 和 `2`。第二个 Worker 成功确认消息，最终会员 42 的余额为 100 积分，事件登记为 1 条，待确认消息为 0 条。

## 官方资料

- [NATS Server 2.12.2](https://github.com/nats-io/nats-server/releases/tag/v2.12.2) · [nats.go 1.47.0](https://github.com/nats-io/nats.go/releases/tag/v1.47.0)
- [Go durable pull consumer 示例](https://examples.nats.io/examples/jetstream/pull-consumer/go/) · [AckWait 与重投](https://docs.nats.io/learn/jetstream/acknowledgment) · [Go DoubleAck 示例](https://examples.nats.io/examples/jetstream/ack-ack/go/)
- [SQLite 事务](https://www.sqlite.org/lang_transaction.html) · [SQLite UPSERT](https://www.sqlite.org/lang_upsert.html) · [go-sqlite3 编译说明](https://github.com/mattn/go-sqlite3/tree/v1.14.33#compilation)
