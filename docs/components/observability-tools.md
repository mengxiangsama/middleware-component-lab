# 可观测性工具

## Grafana

- **常用功能**。查询数据源、临时排查和保存仪表盘。Explore 适合交互式查询，Dashboard 用于重复查看同一组面板。 [数据源](https://grafana.com/docs/grafana/latest/datasources/) · [Explore](https://grafana.com/docs/grafana/latest/visualizations/explore/)
- **最小接入**。在 Connections 添加 Prometheus 数据源，填写 Grafana 服务端可访问的 URL 和所需认证，保存并测试连接。在 Explore 选择该数据源，查询 `up` 并缩小时间范围。新建 Dashboard，添加使用同一数据源和查询的 Time series 面板，再保存仪表盘。 [第一个仪表盘](https://grafana.com/docs/grafana/latest/fundamentals/getting-started/first-dashboards/)
- **验证**。保留 Prometheus 的抓取配置，停止一个测试目标。确认 Explore 和面板在同一时间段都显示该目标的 `up` 从 1 变为 0。连接测试成功但图表为空时，用固定时间范围验证原始查询，再检查面板数据源、变量和查询条件。

## Alertmanager

- **常用功能**。接收 Prometheus 已触发的告警，按标签分组和路由，去重后发给通知接收方。Silence 用标签匹配和期限临时静默通知。Inhibition 在匹配的源告警存在时抑制目标告警通知，例如同一 `cluster` 的上游故障抑制次生告警。 [告警分组、抑制和静默](https://prometheus.io/docs/alerting/latest/alertmanager/)
- **最小接入**。在 `prometheus.yml` 的 `alerting.alertmanagers` 配置接收地址，用 `rule_files` 加载告警规则。在 Alertmanager 的 `route` 设置默认 `receiver`，用 `group_by: [alertname, job]` 合并同类告警，再为测试接收方配置 `webhook_configs`。按告警标签分发时，在子路由配置 `matchers`。 [Prometheus 发送配置](https://prometheus.io/docs/prometheus/latest/configuration/configuration/#alertmanager_config) · [告警规则](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/) · [Alertmanager 路由和接收方](https://prometheus.io/docs/alerting/latest/configuration/)
- **验证**。以 `up{job="demo"} == 0` 建测试规则，停止同一 job 的两个已配置目标。让两个告警在 `group_wait` 内进入同组，核对 Webhook 是否收到包含两个实例的通知。恢复目标并确认告警消除后，给其中一个实例配置短时 Silence 再演练。确认 Prometheus 仍显示两个告警，Webhook 仅通知未被静默的实例。测试恢复通知时确认接收方已启用 `send_resolved`。

运行 [Prometheus + Alertmanager 实验](../../labs/alertmanager-webhook/README.md)，让两个订单实例同时抓取失败，核对同一分组的触发与恢复通知各包含两个实例。

## Grafana Tempo

- **常用功能**。保存并查询分布式请求的 trace，通过 Trace ID 定位一次请求，或用 TraceQL 找到指定服务的错误 span。 [Tracing 入门](https://grafana.com/docs/tempo/latest/set-up-for-tracing/) · [TraceQL 示例](https://grafana.com/docs/tempo/latest/traceql/construct-traceql-queries/)
- **最小接入**。在 Tempo 启用可访问的 OTLP receiver。将 [OpenTelemetry Collector](service-tools.md#opentelemetry) 的 traces pipeline 指向 Tempo 的 OTLP gRPC 或 HTTP 接收端，匹配协议与 TLS。令测试服务以 `service.name=demo-api` 导出追踪，并发出一次测试请求。在 Grafana 增加 Tempo 数据源并连接查询端，在 Explore 按 Trace ID 查看完整链路。也可以用 `{ resource.service.name = "demo-api" && status = error }` 搜索标记为错误的 span。 [Collector 接入](https://grafana.com/docs/tempo/latest/set-up-for-tracing/instrument-send/set-up-collector/otel-collector/) · [Tempo 数据源设置](https://grafana.com/docs/grafana/latest/datasources/tempo/configure-tempo-data-source/)
- **验证**。让测试接口分别成功和失败，确认错误查询命中失败请求。在 Explore 找不到已知 Trace ID 时，检查 Collector 导出日志、Tempo receiver 监听和采样结果。再用查询端的 `GET /api/traces/<traceID>` 验证数据是否能被读出。多租户部署需核对写入与查询使用同一个租户标识。 [查询 API](https://grafana.com/docs/tempo/latest/api_docs/)

## Kibana

- **常用功能**。用 Discover 搜索 Elasticsearch 文档，在结果表查看字段和原始内容，也可以保存常用查询。Data View 指定目标索引、数据流或别名。 [Discover](https://www.elastic.co/docs/explore-analyze/discover) · [Data View](https://www.elastic.co/docs/explore-analyze/find-and-organize/data-views)
- **最小接入**。确认 Kibana 已连到 Elasticsearch，并使用具有对应数据读取及 Data View 权限的账号。在 Data Views 创建 `logs-demo-*`，选择实际时间字段，例如 `@timestamp`。在 Discover 的 classic mode 选择该 Data View，设置包含样本的时间范围，用 KQL 查询 `service.name: "demo-api" AND log.level: ERROR`。字段以实际映射为准。 [KQL 筛选语法](https://www.elastic.co/docs/reference/query-languages/kql)
- **验证**。写入一条已知时间和字段的测试日志，确认切换时间范围时结果相应变化。索引已有文档但 Discover 无结果时，逐步清除 KQL 和固定筛选条件，再核对索引匹配、时间字段及文档读取权限。 [Elasticsearch 写入和刷新](search-cache.md#elasticsearch)
