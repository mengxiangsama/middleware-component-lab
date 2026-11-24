# NATS JetStream KeyValue + Go 并发更新导入进度

## 适用场景

批量导入任务分给多个 Worker，每个 Worker 处理不同的数据分片。两个 Worker 都读到已完成 10 条，各自处理了 2 条和 3 条。如果直接写回计算好的 `12` 和 `13`，后一次写入会覆盖前一次。用 JetStream KeyValue 的比较并交换（compare-and-swap，CAS），服务端只接受带有当前版本的更新，发生冲突的 Worker 重新读取进度和版本，再计算新值。

| 组件 | 职责 |
| --- | --- |
| NATS JetStream KeyValue | 用 bucket 存放任务进度，为每次写入生成 revision，并在服务端校验 `Update` 携带的 expected revision。 |
| Go Worker | 通过 `nats.go/jetstream` 获取当前进度，提交本分片的增量，并在确认发生版本冲突后重新计算。 |

## 接入步骤

1. 在启用了 JetStream 的服务端创建 `IMPORT_PROGRESS` bucket。在分发任务前用 `Create` 初始化进度 key，例如 `batch.42.completed`，值为 `10`。各 Worker 连接同一个 NATS 服务后通过 `KeyValue` 打开这个 bucket。
2. Worker 完成分片后，用 `Get` 读取当前值和 `Revision()`。加上本分片的完成条数，再调用 `Update(ctx, key, nextValue, revision)`。返回成功时，服务端接受了这次条件写入。
3. 遇到明确的 revision 冲突时，从 `Get` 重做一次读取、计算和条件写入。此版本的 Go 客户端可用 `errors.Is(err, jetstream.ErrKeyExists)` 识别服务端的 `wrong last sequence`（`10071`）。在业务代码中为重试设置次数上限和总超时。
4. 为任务维护稳定的分片 ID。若业务需要抵御重复分片和网络超时后的重复提交，在同一个 CAS 值内记录已累计的分片 ID，或用持久化明细记录分片并从明细汇总。请求超时时服务端可能已接受写入，应用需要按分片 ID 核对结果，再决定是否补记。

## 本地验证

准备 Go 1.24 或更高版本，首次运行需要下载 Go 模块。从仓库根目录运行。

```sh
(cd labs/nats-kv-cas && go run -mod=readonly .)
```

程序启动官方 NATS Server Go 模块提供的服务端，绑定 `127.0.0.1` 的可用端口，并将 JetStream 数据写入临时目录。退出时关闭两个客户端和服务端，再清理临时目录。两条独立 TCP 连接都在任何 Worker 写入前读取同一个 key，程序按下表安排读写顺序。

| 步骤 | 断言 |
| --- | --- |
| A 与 B 初次读取 | 两者得到 `10` 和相同的 revision。 |
| A 带读到的 revision 提交 `+2` | 写入成功，revision 推进。 |
| B 带旧 revision 提交 `+3` | 收到版本冲突，随后读取时值仍为 `12`，revision 仍是 A 写入的版本。 |
| B 重新读取 `12` 后提交 `+3` | 写入成功，A 读取到该次写入的 revision 和最终值 `15`。 |

## 实测记录

2026-11-24 在 macOS 26.5 ARM64 上使用 Go 1.24.2、NATS Server 2.12.2 和 nats.go 1.47.0 运行，`go mod verify` 通过。两条连接都读取到 `10`、revision `1`。A 写入后为 `12`、revision `2`，B 使用 revision `1` 收到 `400/10071 wrong last sequence: 2`。B 重新读到 `12`、revision `2` 后提交，最终为 `15`、revision `3`。本次验证范围为单节点、一个 key 的条件更新和一次明确冲突后的重试。

## 官方资料

- [NATS Key-Value Store 与 revision](https://docs.nats.io/learn/key-value/history-and-revisions)
- [NATS KV Go 示例](https://examples.nats.io/examples/kv/intro/go/)
- [nats.go 1.47.0 的 KeyValue 接口](https://github.com/nats-io/nats.go/blob/v1.47.0/jetstream/kv.go)
- [NATS Server 2.12.2](https://github.com/nats-io/nats-server/releases/tag/v2.12.2)
