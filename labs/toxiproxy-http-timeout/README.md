# Toxiproxy + Java HttpClient 复现库存请求超时

## 适用场景

库存服务平时会快速返回，网络变慢时调用方仍要在约定时间内退出。Toxiproxy 作为 TCP 代理给响应方向增加固定延迟，Java 调用方用原来的 HTTP 请求验证超时和恢复。

| 组件 | 分工 |
| --- | --- |
| Toxiproxy | 监听本地 TCP 端口并转发到库存服务。通过控制 API 添加或移除延迟。 |
| JDK `HttpClient` | 发出库存查询，分别设置连接超时和单次请求超时。 |
| 本地库存服务 | 使用 JDK `HttpServer` 返回 HTTP 200 和 `stock=7`，记录实际收到的请求数。 |

`Java HttpClient → Toxiproxy TCP 监听端口 → 库存 HTTP 服务`

## 接入步骤

1. 创建代理，把 `listen` 绑定到调用方可访问的端口，将 `upstream` 指向库存服务。应用使用代理监听端口作为库存服务地址。实验把三个监听端口都限制在 `127.0.0.1`。代理的监听端口随运行分配，通过控制 API 读取实际地址。[代理配置](https://github.com/Shopify/toxiproxy/blob/v2.13.0/README.md#configuration)
2. 在 Java 客户端用 `HttpClient.Builder.connectTimeout` 给建立新连接设置 2 秒上限，用 `HttpRequest.Builder.timeout` 给本次请求设置 1 秒。本实验延迟的是已到达后端的查询响应，验证等待响应时抛出 `HttpTimeoutException`。[JDK 17 连接超时](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.Builder.html#connectTimeout(java.time.Duration)) · [JDK 17 请求超时](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html#timeout(java.time.Duration))
3. 通过 `POST /proxies/{name}/toxics` 添加 `latency`，方向设为 `downstream`，延迟设为 3500 毫秒，`jitter` 设为 0。复现后用 `DELETE /proxies/{name}/toxics/slow-response` 移除延迟，再发同一个查询。[延迟注入](https://github.com/Shopify/toxiproxy/blob/v2.13.0/README.md#latency) · [控制 API](https://github.com/Shopify/toxiproxy/blob/v2.13.0/README.md#http-api)

实验用只读 GET 验证。请求超时时，服务端可能已经处理成功。业务写操作可以借助幂等键核对已有结果，再决定是否重试。

## 本地验证

安装 JDK 17 或更高版本。从 [Toxiproxy 2.13.0 发布页](https://github.com/Shopify/toxiproxy/releases/tag/v2.13.0) 选择匹配系统和架构的服务端二进制，对照发布页的 SHA-256 校验后加上执行权限。从仓库根目录运行。

```sh
java labs/toxiproxy-http-timeout/HttpTimeoutDemo.java /path/to/toxiproxy-server
```

如果 `toxiproxy-server` 已位于 `PATH`，可以省略最后一个参数。程序启动独立的 Toxiproxy 和本地库存服务，完成测试后清理进程和临时配置。

| 阶段 | Java 调用结果 | 后端累计请求 |
| --- | --- | --- |
| 原始链路 | HTTP 200，`stock=7` | 1 |
| 增加 3500 毫秒响应延迟 | 约 1 秒抛出 `HttpTimeoutException` | 2 |
| 移除延迟后重新查询 | HTTP 200，`stock=7` | 3 |

## 实测记录

2026-11-27 在 macOS 26.5 ARM64、OpenJDK 17.0.16 与 Toxiproxy 2.13.0 上分别以显式路径和 `PATH` 运行，两次均通过。添加响应延迟后，Java 两次均在约 1002 毫秒抛出 `HttpTimeoutException`，此时后端已经收到第 2 次请求。移除延迟后，新的请求恢复为 HTTP 200，后端累计收到 3 次请求。Toxiproxy 二进制通过 [官方发布文件](https://github.com/Shopify/toxiproxy/releases/expanded_assets/v2.13.0) 所列 SHA-256 校验。

实验覆盖单机上的 TCP 响应延迟与移除后的新请求恢复。要继续验证应用层重试与熔断，可运行 [库存服务 503 演练](../resilience4j-inventory/README.md)。
