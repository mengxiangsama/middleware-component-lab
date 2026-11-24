# Prometheus + Blackbox Exporter HTTP 就绪探测

订单 API 在发布或故障恢复期间可能返回 HTTP 503。用 Blackbox Exporter 从监控节点请求 API 的 `/actuator/health/readiness`，再用 Prometheus 采集探测结果，就能看到 API 什么时候无法接流量、什么时候恢复。这个实验补上从健康端点到 Prometheus 可查询指标的接入过程。

- Spring Boot Actuator 提供应用就绪端点。实验里的 Python HTTP 服务模拟同一路径，正常时返回 HTTP 200 和 `{"status":"UP"}`，切到不可就绪时返回 HTTP 503 和 `{"status":"OUT_OF_SERVICE"}`。
- Blackbox Exporter 负责发送 HTTP 请求。`http_readiness` 模块只把 HTTP 200 计为成功，并记录收到的状态码。
- Prometheus 定期抓取 Exporter 的 `/probe`。`up` 表示这次指标抓取是否成功，`probe_success` 表示应用探测是否成功，`probe_http_status_code` 给出应用返回的状态码。

## 运行实验

准备 Python 3，以及适合本机系统和架构的 [Prometheus 3.16.0](https://github.com/prometheus/prometheus/releases/tag/v3.16.0) 和 [Blackbox Exporter 0.29.1](https://github.com/prometheus/blackbox_exporter/releases/tag/v0.29.1) 官方发行包。按各发行页的 `sha256sums.txt` 校验后解压，把 `prometheus` 和 `blackbox_exporter` 所在目录加到 `PATH`。

在仓库根目录运行：

```bash
python3 labs/prometheus-blackbox-http/verify.py
```

也可以显式传入两个二进制路径：

```bash
python3 labs/prometheus-blackbox-http/verify.py \
  --prometheus /path/to/prometheus \
  --blackbox-exporter /path/to/blackbox_exporter
```

脚本启动真实的 Prometheus 和 Blackbox Exporter，给本地三个服务分配回环端口，然后主动切换 fixture 的健康状态。每次切换后，它都通过 Prometheus `/api/v1/query` 等待实际抓到下一组指标，并检查 `instance` 对应 fixture URL。实验结束时脚本关闭服务并清理临时 TSDB。

## 实测结果

2026-11-24 在 macOS 26.5 ARM64 和 Python 3.13.7 上运行，使用校验过 SHA-256 的两个官方发行包。

```text
ready: up=1, probe_success=1, probe_http_status_code=200
unready: up=1, probe_success=0, probe_http_status_code=503
recovered: up=1, probe_success=1, probe_http_status_code=200
```

中间一行说明 Prometheus 收到了 Exporter 返回的指标，同时被探测的 API 回了 503。三次观测都能抓到指标，只有应用不可就绪那次的 `probe_success` 为 `0`。测试覆盖单个本地 HTTP 端点的成功、失败和恢复，探测结果以 HTTP 状态码为准。

## 接到实际服务

1. 确认 Blackbox Exporter 所在节点能访问应用就绪端点，使用 [Blackbox Exporter 配置](blackbox.yml) 加载 `http_readiness` 模块。
2. 复制 [Prometheus 抓取模板](prometheus.yml.tmpl) 到 Prometheus 配置。把 `${target}` 换成应用就绪端点的完整 URL，例如 `http://order-api:8085/actuator/health/readiness`，把 `${exporter}` 换成 Exporter 的 `host:port`。抓取配置把应用 URL 送进 `/probe` 的 `target` 参数，用同一个 URL 标记 `instance`，并把实际抓取地址改到 Exporter。
3. 在 Prometheus 查询 `probe_success{job="readiness_probe"}` 看应用就绪状态，查询 `up{job="readiness_probe"}` 检查探测指标能否被抓取，并结合 `probe_http_status_code{job="readiness_probe"}` 核对返回码。

## 官方资料

- [Blackbox Exporter 源码](https://github.com/prometheus/blackbox_exporter/tree/v0.29.1)、[配置示例](https://github.com/prometheus/blackbox_exporter/blob/v0.29.1/blackbox.yml)和 [HTTP 探测配置](https://github.com/prometheus/blackbox_exporter/blob/v0.29.1/CONFIGURATION.md#http_probe)
- [Prometheus 多目标 Exporter 接入](https://prometheus.io/docs/guides/multi-target-exporter/)和[即时查询 API](https://prometheus.io/docs/prometheus/latest/querying/api/#instant-queries)
- [Spring Boot Actuator 的 Kubernetes 探针](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes)
