# 用 Fluent Bit 合并 Java 异常堆栈

## 适用场景

Java 应用把错误标题、堆栈帧和异常原因分多行写入同一个文本文件。采集时应把每次异常保留为一条日志事件，便于在下游检索完整的堆栈。本实验用两个紧挨着的异常和两条普通日志检查事件边界。

## 接入步骤

1. 用 Tail 读取 [样例订单日志](orders.txt)。样例首行格式为 `yyyy-MM-dd HH:mm:ss.SSS`、日志级别和正文。写入生产文件时采用的前缀如果不同，先取样核对实际格式。
2. 在 [`fluent-bit.yaml`](fluent-bit.yaml) 中把自定义解析器挂到 Tail 的 `multiline.parser`。`start_state` 匹配带时间戳的 `ERROR` 标题，`cont` 接收堆栈帧、`Caused by:`、`Suppressed:` 和 `... N more` 等续行。新的日志标题会结束上一条事件。
3. 用 stdout 的 `json_lines` 格式检查 `log` 字段。每个 JSON 对象是一条事件，堆栈换行保留在该对象的 `log` 字符串里。

## 验证方法

准备 Docker、Shell 和 `jq`。首次运行会拉取官方镜像 `cr.fluentbit.io/fluent/fluent-bit:5.2.0`。从仓库根目录运行以下命令。

```sh
sh labs/fluent-bit-java-multiline/verify.sh
```

容器以只读方式挂载样例，运行期间禁用网络。脚本检查四条事件的行数，并将 `log` 字段拼回后逐行和原文件比较。成功时退出码为 0。

| 输出顺序 | 预期 |
| --- | --- |
| 1 | `checkout started` 独立输出，1 行 |
| 2 | `checkout failed` 连同堆栈、suppressed 异常与 cause，7 行 |
| 3 | `inventory update failed` 连同两行堆栈帧，3 行 |
| 4 | `checkout lab finished` 独立输出，1 行 |

## 接到持续采集时

样例使用 `exit_on_eof: true` 让静态测试自动结束。实测文件以堆栈收尾时，进程可能在最后一条缓冲事件输出前结束，所以样例加了一条普通日志，明确结束第二段堆栈。持续采集要移除 `exit_on_eof`，按真实写入节奏核对 `flush_timeout`，并验证空闲时输出、日志轮转和下游接收。

Docker 或 CRI 日志还可能有容器封装和长行拆分。若同时需要恢复封装片段和应用堆栈，可先用 Tail 的 `docker` 或 `cri` 解析器恢复片段，再验证 Multiline filter 对应用堆栈的处理。Tail 的多个 `multiline.parser` 条目按先匹配者生效。

## 搭配 Loki 集中检索完整堆栈

订单服务部署到多台机器后，排查一次结账失败时，希望按服务找到那条错误，并在同一条结果里看到调用栈和下游超时原因。Fluent Bit 负责读文件、合并堆栈和发送，Loki 负责集中接收、存储以及按标签和正文查询。

`订单日志文件 → Fluent Bit Tail + 多行解析 → Loki 写入接口 → LogQL 查询`

1. 用 [Compose](compose.yaml) 启动 Loki 3.7.10。等 `/ready` 返回成功后运行 Fluent Bit 5.2.0，它在容器网络内访问 `loki:3100`。
2. [Loki 输出配置](fluent-bit-loki.yaml) 延用前面的多行解析，并增加 Loki 输出。它向 `/loki/api/v1/push` 发送事件，使用 `service_name=orders,environment=lab` 标签，`drop_single_key: raw` 保留完整堆栈正文。示例用采集时间作为查询时间，文件原时间保留在正文。
3. 用固定的服务和环境标签缩小查询范围，再按错误正文过滤。真实业务里高基数的订单 ID 可保留在正文或结构化元数据中。

```logql
{service_name="orders",environment="lab"} |= "checkout failed"
```

从仓库根目录运行联调脚本。需要 Docker Compose、`curl` 和 `jq`。脚本等待 Loki 就绪，运行采集，查询四条事件并逐行核对原始输入，再用上面的 LogQL 确认仅返回一条带 `SocketTimeoutException` 的结账错误。

```sh
sh labs/fluent-bit-java-multiline/verify-loki.sh
```

Loki 可能按等级或其他标签把结果分到不同日志流。脚本合并各流并按查询返回的时间排序后再与输入对照。这个实验使用无鉴权的临时单机实例，只在本机回环地址发布随机端口，结束时清理自己的 Compose 项目和实验数据。生产接入需另行配置鉴权、持久存储和保留期，并验证轮转、重试和多实例日志。还可将同一 LogQL 用到配置了 Loki 数据源的 Grafana Explore 中。

[Fluent Bit Loki 输出](https://docs.fluentbit.io/manual/data-pipeline/outputs/loki) · [Loki 镜像配置](https://github.com/grafana/loki/blob/v3.7.10/cmd/loki/loki-docker-config.yaml) · [查询 API](https://grafana.com/docs/loki/latest/reference/loki-http-api/#query-logs-within-a-range-of-time) · [LogQL 日志过滤](https://grafana.com/docs/loki/latest/query/log_queries/#line-filter-expression)

## 官方资料

- [Fluent Bit 官方仓库](https://github.com/fluent/fluent-bit) · [5.2.0 发布记录](https://github.com/fluent/fluent-bit/releases/tag/v5.2.0) · [内置 Java 解析规则源码](https://github.com/fluent/fluent-bit/blob/v5.2.0/src/multiline/flb_ml_parser_java.c)
- [多行解析和自定义正则示例](https://docs.fluentbit.io/manual/data-pipeline/parsers/multiline-parsing#configuration-example) · [Tail 配置](https://docs.fluentbit.io/manual/data-pipeline/inputs/tail) · [Multiline filter](https://docs.fluentbit.io/manual/data-pipeline/filters/multiline-stacktrace)
- [官方 Docker 镜像](https://docs.fluentbit.io/manual/installation/downloads/docker) · [stdout 输出格式](https://docs.fluentbit.io/manual/data-pipeline/outputs/standard-output)
