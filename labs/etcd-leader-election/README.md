# etcd 租约选主与旧任务写入拦截

## 适用场景

两个应用副本都可以执行门店日结。应用需要确定当前由哪个副本负责，在负责副本失联后让另一个接手。etcd 的租约管理候选资格，Election 负责协调接手。日结状态也存放在 etcd 时，可以在同一个事务验证领导资格和写入结果，避免已经失去资格的副本覆盖新结果。

| 组件 | 分工 |
| --- | --- |
| [etcd](../../docs/components/service-tools.md#etcd) | 保存候选键和租约。失去续约后删除租约关联的键，并用事务校验写入是否仍然有效。 |
| etcd Go Client | 给每个候选任务创建独立 Session，通过 Election 竞选，持有领导者键和该键的创建修订号。 |

## 最小接入

1. 两个 Worker 各创建一个 Session。为同一个协调任务使用相同的 Election 前缀，传入各自的 Worker ID 调用 `Campaign`。只有成功返回的 Worker 获得执行资格。
2. 监视 Session 的 `Done()`，失去会话时停止新的业务操作。实验用 `Orphan()` 停止 A 的续约，让服务端等待实际租约过期。等待中的 B 在 A 的候选键删除后接手。
3. 把当前领导者键的创建修订号作为写入条件，在 etcd 事务的 `If` 中比较 `CreateRevision(election.Key()) == election.Rev()`。条件成立时写入结果。应用应检查 `TxnResponse.Succeeded`。

实验中的结果键也在同一 etcd 内，所以资格校验和写入由同一次 etcd 事务完成。业务结果存在外部数据库时，需由该存储校验防止旧主写入的版本号，或对每个业务批次执行持久幂等检查。任务的触发时间与业务日结逻辑由应用负责。

## 本地验证

准备 Go 1.24+ 及可下载模块的网络，从仓库根目录执行。

```sh
(cd labs/etcd-leader-election && go run .)
```

实验在临时目录启动单节点 etcd 3.6.6，仅监听本机回环地址，两个 Worker 使用同一进程内的独立 Session。它检查以下结果。

- A 当选并写入第一笔结果，B 已登记候选且仍在等待。
- A 停止续约后，B 等到实际租约过期再取得资格并写入结果。
- A 携带旧的键创建修订号再次写入，事务条件失败，最后的值仍为 `worker-b:ready`。

实验验证应用级选主和 etcd 内的条件写入。多成员集群故障、外部数据库写入和任务调度由对应的部署环境验证。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、Go 1.24.2 上运行上述命令。etcd 返回版本 3.6.6。A 当选后 B 进入等待，A 的租约过期后 B 接手。服务端拒绝 A 的旧写入，线性一致读取得最终值 `worker-b:ready`。

## 官方资料

- [etcd v3.6.6](https://github.com/etcd-io/etcd/releases/tag/v3.6.6) · [Go Election 源码](https://github.com/etcd-io/etcd/blob/v3.6.6/client/v3/concurrency/election.go)
- [Election API](https://etcd.io/docs/v3.6/dev-guide/api_concurrency_reference_v3/) · [Lease 和 Transaction API](https://etcd.io/docs/v3.6/learning/api/)
- [嵌入 etcd 的官方示例](https://github.com/etcd-io/etcd/blob/v3.6.6/server/embed/doc.go)
