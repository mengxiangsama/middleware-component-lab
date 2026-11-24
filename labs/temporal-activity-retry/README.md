# Temporal + Go 在库存接口短暂失败后重试 Activity

订单流程调用库存接口时，接口可能已经完成预留，却因短暂故障返回 HTTP 503。此时调用方需要重试，库存接口需要识别重复的业务操作。本例用 Temporal 管理 Activity 的重试，Go Worker 调用库存接口。库存服务用订单 ID 作为幂等键，重复请求直接复用已经生成的预留号。

## 接入步骤

1. 在 Go Worker 上注册 Workflow 和 Activity。Workflow 负责安排步骤，通过 `workflow.ExecuteActivity` 调用 Activity。HTTP 请求由 Activity 执行。
2. 配置 Activity 的 `RetryPolicy`。本例最多尝试 3 次，首次重试间隔是 250 毫秒，每次失败后间隔翻倍。单次执行最多 3 秒，从首次调度到结束的总时间最多 12 秒。
3. 每次调用库存接口时，通过 `Idempotency-Key` 发送同一个业务操作 ID。对 HTTP 503 返回可重试错误。接口按同一个键返回预留号，Workflow 通过 Activity 的结果获得预留号。

## 运行验证

需要 Go 1.23 或更新版本，以及 Temporal CLI 1.5.1。以下命令将官方 macOS arm64 发行版解压到临时目录，其他平台可下载对应发行包。

```bash
temporal_dir=$(mktemp -d)
(
  cd "$temporal_dir" || exit
  release=https://github.com/temporalio/cli/releases/download/v1.5.1
  archive=temporal_cli_1.5.1_darwin_arm64.tar.gz
  curl -fsSLO "$release/$archive" &&
    curl -fsSLO "$release/checksums.txt" &&
    awk -v archive="$archive" '$2 == archive' checksums.txt | shasum -a 256 -c - &&
    tar -xzf "$archive"
)
"$temporal_dir/temporal" server start-dev --ip 127.0.0.1 --port 17233 --headless
```

保持开发服务器运行。在第二个终端从仓库根目录执行。

```bash
(cd labs/temporal-activity-retry && go run -mod=readonly .)
```

验证程序启动一个本地 HTTP 库存测试服务，第一次请求先写入预留，再故意返回 HTTP 503。第二次请求命中相同幂等键，直接返回已生成的预留号。程序断言两个 HTTP 状态、预留数量、Activity 尝试次数和 Workflow 返回值。

实测使用 macOS 26.5 arm64、Temporal CLI 1.5.1（Server 1.29.1）、Go 1.24.2 和 Temporal Go SDK 1.37.0。

```text
HTTP request statuses: [503 200]
Workflow result: receipt=reserved-order-123, activity_attempt=2
Reservations applied: 1
```

验证程序结束后会关闭 Worker 和本地 HTTP 服务。在第一个终端按 Ctrl+C 停止 Temporal，随后可以删除临时下载目录。

示例的库存状态保存在测试服务内存中。实际接入时，库存服务应持久化幂等键和预留结果，并保证重复请求与首次写入并发时仍只生成一笔预留。Activity 的外部操作可能被执行多次，本例的一次预留来自库存服务对幂等键的处理。

## 官方资料

- [Temporal CLI 1.5.1 官方发行包与校验文件](https://github.com/temporalio/cli/releases/tag/v1.5.1)
- [Temporal CLI 开发服务器命令](https://docs.temporal.io/cli/command-reference/server)
- [Temporal Go SDK v1.37.0](https://github.com/temporalio/sdk-go/tree/v1.37.0)
- [Temporal Go SDK Activity 重试示例](https://github.com/temporalio/samples-go/tree/main/retryactivity)
- [Temporal 重试策略](https://docs.temporal.io/encyclopedia/retry-policies)
- [Activity 定义与幂等性](https://docs.temporal.io/activity-definition)
