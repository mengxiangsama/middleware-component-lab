# OpenTelemetry Java 跨 HTTP 传播 Trace Context

订单服务通过 JDK `HttpClient` 查询库存时，把订单侧的客户端 span 上下文写入 HTTP `traceparent`。库存服务从入站请求提取它，把服务端 span 接到同一条 trace 上。适合给缺少自动插桩的自定义 HTTP 边界补接链路，或者用测试定位 trace 在哪一跳断开。

实验在同一个 JVM 内启动两个独立配置的 OpenTelemetry Java SDK，模拟 `orders` 和 `inventory` 两个服务。请求通过 JDK `HttpClient` 和绑定到 `127.0.0.1` 随机端口的 `HttpServer` 实际收发。两侧 span 通过 SDK 的 `SimpleSpanProcessor` 导出到各自的内存 exporter。

## 接入步骤

1. 应用启动时构建 SDK 并注册 `W3CTraceContextPropagator`。为两侧设置独立的 `service.name`。本例的 SDK 配置在 [测试类](src/test/java/io/github/middlewarelab/otelhttp/HttpPropagationTest.java) 的 `telemetry` 方法中。
2. 出站侧创建 `CLIENT` span，在它的作用域内调用 `inject`，使用 `HttpRequest.Builder#setHeader` 写入传播头，再发送请求。关闭作用域并结束 span。
3. 入站侧通过 `extract` 从 HTTP 头提取远端上下文。把该上下文传给 `SERVER` span 的 `setParent`，用服务端 span 的作用域执行业务 handler，并在 `finally` 结束 span。两侧接线见 [InventoryHttpTracing](src/main/java/io/github/middlewarelab/otelhttp/InventoryHttpTracing.java)。

## 运行与验证

需要 JDK 17 或更高版本。首次运行通过 Maven 下载依赖。从仓库根目录执行以下命令。

```bash
./mvnw --batch-mode -f labs/otel-java-http-propagation/pom.xml clean verify
```

| 请求 | 实测断言 |
| --- | --- |
| 订单侧注入 W3C 传播头 | 库存端实际收到 `00-<trace-id>-<client-span-id>-<flags>`。两个 SDK 各导出一个 span，二者的 trace ID 相同，库存端 span 的父 ID 是订单侧客户端 span ID，父上下文标记为远端。 |
| 请求缺少 `traceparent` | 库存端生成独立根 span。 |
| `traceparent` 中 trace ID 全为零 | 库存端收到该请求头并将它当作无效上下文，生成有效的独立根 span。 |

三次调用都返回 HTTP 200。响应正文用于在实验内核对 handler 当前作用域的 trace ID，测试报告位于 `labs/otel-java-http-propagation/target/surefire-reports/`。实验验证一次 HTTP 跳转与两侧 SDK 记录的 span 父子关系，使用内存 exporter 即可运行。

## 官方资料

- [Java API 的 HTTP 注入与提取示例](https://opentelemetry.io/docs/languages/java/api/#contextpropagators)
- [Java SDK 配置](https://opentelemetry.io/docs/languages/java/sdk/)
- [W3C Trace Context 的 traceparent 格式](https://www.w3.org/TR/trace-context/#traceparent-header)
- [W3C Trace Context Level 2 的随机 trace ID 标志](https://www.w3.org/TR/trace-context-2/#random-trace-id-flag)
- [OpenTelemetry Java v1.62.0 官方仓库](https://github.com/open-telemetry/opentelemetry-java/tree/v1.62.0) · [W3C 传播器源码](https://github.com/open-telemetry/opentelemetry-java/blob/v1.62.0/api/all/src/main/java/io/opentelemetry/api/trace/propagation/W3CTraceContextPropagator.java)
