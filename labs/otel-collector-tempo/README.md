# OpenTelemetry Collector + Grafana Tempo 查询下单链路

## 适用场景

下单请求变慢时，可以用同一个 Trace ID 找到入口和库存调用，比较两段耗时。这个实验用两条模拟 span 验证 Collector 能否把数据转发给 Tempo，以及 Tempo 能否还原服务名、父子关系和耗时。

| 组件 | 分工 |
| --- | --- |
| [OpenTelemetry Collector](../../docs/components/service-tools.md#opentelemetry) | 接收应用的 OTLP/HTTP 请求，用 traces pipeline 向后端导出 span。 |
| [Grafana Tempo](../../docs/components/observability-tools.md#grafana-tempo) | 从 OTLP/gRPC 接口接收 span，按 Trace ID 提供查询。 |

## 接入步骤

1. 应用把 trace 发送到 Collector 的 OTLP/HTTP receiver，实验地址为 `http://127.0.0.1:18618/v1/traces`。入口和下游 span 使用同一个 Trace ID，各自填写 `service.name`，库存 span 的 `parentSpanId` 指向下单 span。
2. 在 [collector.yaml](collector.yaml) 的 `service.pipelines.traces` 中连接 `otlp` receiver 和 `otlp/tempo` exporter。Exporter 使用 OTLP/gRPC，地址填写 Tempo 的 `127.0.0.1:18617`。本机实验使用明文连接，跨主机部署按环境设置 TLS 和认证。
3. 在 [tempo.yaml](tempo.yaml) 的 `distributor.receivers.otlp.protocols.grpc` 配置对应端口。Tempo 使用单进程和本地存储，查询走独立的 HTTP 端口 `18320`。
4. 用 `GET /api/traces/<traceID>` 读回链路，核对 `service.name`、`parentSpanId` 和起止时间。排查真实请求也可以在 Grafana 的 Tempo 数据源中按相同 Trace ID 查看。

## 本地验证

准备 Python 3.8+，并将 OpenTelemetry Collector 0.128.0 的 `otelcol` 和 Tempo 2.8.2 的 `tempo` 加入 `PATH`。Tempo 2.8.2 可在 Linux 使用发布包，macOS 可从该版本官方源码构建。在解压后的源码目录运行以下命令，目标目录需已存在。

```sh
GOTOOLCHAIN=go1.24.4 go build -o /path/to/bin/tempo ./cmd/tempo
```

从本仓库根目录运行。

```sh
python3 labs/otel-collector-tempo/verify.py
```

脚本在临时目录启动两个进程，使用回环端口 `18320`、`18321`、`18322`、`18617` 和 `18618`。Tempo 就绪后，脚本发送带有随机 Trace ID 的两条模拟 span。它检查 Collector 的接收结果，再通过 Tempo 查询该 Trace ID，核对以下结果。

| span | 验证结果 |
| --- | --- |
| `checkout-api` 的 `POST /checkout` | 根 span，耗时 240 ms |
| `inventory-api` 的 `Reserve stock` | 与入口属于同一个 Trace ID，父 span 指向入口，耗时 200 ms |

2026-11-24 在 macOS ARM64 上，使用 Collector 原生发行版 0.128.0 和由 Go 1.24.4 构建的 Tempo 2.8.2 运行脚本，Tempo 返回了上述两条 span。实测覆盖本机 OTLP 转发和 Trace ID 查询，应用接入 SDK、跨进程传播和持久存储恢复可在对应环境单独验证。

## 官方资料

- [OpenTelemetry Collector 0.128.0](https://github.com/open-telemetry/opentelemetry-collector-releases/releases/tag/v0.128.0) · [Collector 配置](https://opentelemetry.io/docs/collector/configuration/) · [OTLP 规范](https://opentelemetry.io/docs/specs/otlp/)
- [Grafana Tempo 2.8.2](https://github.com/grafana/tempo/releases/tag/v2.8.2) · [本地配置示例](https://github.com/grafana/tempo/blob/v2.8.2/example/docker-compose/local/tempo.yaml) · [Tempo HTTP API](https://grafana.com/docs/tempo/latest/api_docs/)
