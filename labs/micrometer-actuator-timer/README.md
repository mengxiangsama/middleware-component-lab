# Micrometer Timer + Spring Boot Actuator + Prometheus

## 适用场景

发货资格检查偶尔变慢时，需要知道通过和拒绝各有多少次，以及各组有多少次在 50 毫秒内完成。Spring Boot 接收 HTTP 请求，Micrometer 的 `Timer` 记录业务检查耗时和结果，Actuator 暴露指标，Prometheus 定期抓取并通过 PromQL 查询结果。

## 接入步骤

1. 在 Spring Boot 项目中加入 `spring-boot-starter-actuator` 与 `micrometer-registry-prometheus`。示例用 Spring Boot 3.5.16，由其 BOM 管理 Micrometer 1.15.12。[实验 POM](pom.xml)
2. 注入 `MeterRegistry`，用 `Timer.start(registry)` 在业务检查前计时。得到业务结果后，调用 `sample.stop(timer)`，按 `result=accepted` 或 `result=rejected` 计入对应 Timer。用 `serviceLevelObjectives(50ms, 250ms)` 配置直方图边界。标签只用固定枚举值，发货单号留在业务日志或追踪中。[Java 示例](src/main/java/lab/shipment/ShipmentApplication.java)
3. 在应用中将 `prometheus` 加入 `management.endpoints.web.exposure.include`。让 Prometheus 配置应用实例的地址与 `metrics_path`。本实验只监听本机回环地址，发布时应根据实际环境控制管理端点的访问范围。[应用配置](src/main/resources/application.yaml)

```yaml
scrape_configs:
  - job_name: shipment-lab
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['127.0.0.1:8080']
```

## 运行与验证

准备 JDK 17+、Python 3 和 [Prometheus 3.16.0 官方二进制](https://github.com/prometheus/prometheus/releases/tag/v3.16.0)。把命令中的路径换成本机解压后的二进制。在仓库根目录执行：

```sh
./mvnw -B -ntp -f labs/micrometer-actuator-timer/pom.xml clean package
python3 labs/micrometer-actuator-timer/verify.py /path/to/prometheus
```

脚本启动 Spring Boot 和 Prometheus 两个本地进程，发出普通通过、拒绝、慢速通过三次业务请求，并检查 HTTP 响应、Actuator 直方图和 Prometheus 查询结果。示例用 150 毫秒等待模拟慢依赖。脚本根据响应中的实验耗时头逐次对照 50 毫秒和 250 毫秒的桶，并检查两个结果标签各自的 `+Inf` 桶与总次数。进程和临时时序数据由脚本清理。

在 macOS 26.5 ARM64、OpenJDK 17.0.16 与 Prometheus 3.16.0 上，实测 HTTP 状态为 200、422、200，慢速检查约 155 毫秒，`up{job="shipment-lab"}` 为 1。以下为一次实际采集结果。

| `result` | 50 毫秒以内 | 250 毫秒以内 | `+Inf` 与总次数 |
| --- | ---: | ---: | ---: |
| `accepted` | 1 | 2 | 2 |
| `rejected` | 1 | 1 | 1 |

## 从指标回答业务问题

Actuator 导出的 `shipment_validation_seconds_count` 是累计次数，`shipment_validation_seconds_sum` 是累计耗时，单位为秒。直方图的 `le` 是包含上界的累计桶。在 Prometheus 中查 `shipment_validation_seconds_bucket{job="shipment-lab",result="accepted",le="0.05"}`，就能看到通过请求中耗时不超过 50 毫秒的累计次数。

需要跨副本查看最近 5 分钟通过请求的达标比例时，可以按目标范围求和后再相除。分母为零表示该窗口没有该类请求。

```promql
sum(rate(shipment_validation_seconds_bucket{job="shipment-lab",result="accepted",le="0.05"}[5m]))
/
sum(rate(shipment_validation_seconds_count{job="shipment-lab",result="accepted"}[5m]))
```

本地实测范围是单应用实例、三次请求、静态目标抓取和即时查询。上述 5 分钟表达式用于持续采集环境。

## 官方参考

- [Micrometer Timer](https://docs.micrometer.io/micrometer/reference/concepts/timers.html) · [直方图与 SLO 桶](https://docs.micrometer.io/micrometer/reference/concepts/histogram-quantiles.html) · [Micrometer 1.15.12 源码](https://github.com/micrometer-metrics/micrometer/tree/v1.15.12)
- [Spring Boot Actuator Prometheus 导出](https://docs.spring.io/spring-boot/3.5/reference/actuator/metrics.html#actuator.metrics.export.prometheus)
- [Prometheus 示例配置](https://github.com/prometheus/prometheus/blob/v3.16.0/documentation/examples/prometheus.yml) · [即时查询 HTTP API](https://prometheus.io/docs/prometheus/latest/querying/api/#instant-queries) · [直方图实践](https://prometheus.io/docs/practices/histograms/)
