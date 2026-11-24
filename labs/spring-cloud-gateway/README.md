# Spring Cloud Gateway 按 Header 灰度路由

商品目录有稳定版和灰度版，测试人员需要通过同一个入口访问灰度版，普通请求仍落到稳定版。Spring Cloud Gateway 根据请求路径和发布标记选路，两个 HTTP 上游在响应中返回自己的版本和收到的标记，用于核对实际流向。实验使用 JDK `HttpServer` 模拟这两个上游。

## 接入步骤

1. 在 Spring Boot 应用导入匹配的 `spring-cloud-dependencies` BOM，再添加 `spring-cloud-starter-gateway-server-webflux`。实验使用 Spring Boot 3.5.16、Spring Cloud 2025.0.3 和 Gateway 4.3.5。[WebFlux Starter](https://docs.spring.io/spring-cloud-gateway/reference/4.3/spring-cloud-gateway-server-webflux/starter.html) · [版本兼容表](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions#supported-releases) · [2025.0.3 发布说明](https://github.com/spring-cloud/spring-cloud-release/releases/tag/v2025.0.3)
2. 在 `spring.cloud.gateway.server.webflux.routes` 下为同一个请求路径配置两个目标，分别把 `uri` 指向稳定版和灰度版的 HTTP 地址。实验由测试把两个随机端口传给 [路由配置](src/test/resources/application.yml)。[4.3.5 上游配置示例](https://github.com/spring-cloud/spring-cloud-gateway/blob/v4.3.5/spring-cloud-gateway-server/src/test/resources/application.yml)
3. 为灰度路由同时设置 `Path=/catalog/**` 和 `Header=X-Release-Track, ^canary$`，用 `order: -1` 让它优先于只有路径条件的稳定路由。如果生产入口允许来自公网的请求，用受信任的入口设置标记，并清除外部传入的同名 Header。[Header 与 Path 谓词](https://docs.spring.io/spring-cloud-gateway/reference/4.3/spring-cloud-gateway-server-webflux/request-predicates-factories.html#the-header-route-predicate-factory)

## 本地验证

使用 JDK 17，从仓库根目录运行以下命令。测试在回环地址和随机端口启动 WebFlux 网关及两个 HTTP 上游，然后依次发请求。

```sh
./mvnw -B -f labs/spring-cloud-gateway/pom.xml verify
```

| 请求 | 核对结果 |
| --- | --- |
| `GET /catalog/items`，Header 为 `canary` | HTTP 200，灰度版收到 `canary` |
| 同一路径，无 Header | HTTP 200，稳定版收到无标记请求 |
| 同一路径，Header 为 `canary-preview` | HTTP 200，稳定版收到原 Header，验证精确匹配 |
| `GET /different/items`，Header 为 `canary` | HTTP 404，两个上游均未收到请求 |
| 停止灰度上游后，重发带 `canary` 的请求 | 网关返回 HTTP 500，稳定版的请求计数保持不变 |
| 灰度上游停止后，重发无 Header 的请求 | HTTP 200，稳定版继续提供服务 |

2026-11-24 在 macOS ARM64、JDK 17.0.16 上通过 [集成测试](src/test/java/io/github/middlewarelab/gateway/HeaderRoutingTest.java)。测试依次验证了表中的六个请求。模拟端口关闭时，日志中的 `Connection refused` 对应表中的 HTTP 500。

实验覆盖入口到一个上游的 HTTP 路由、Header 转发和灰度端口关闭时的响应。需要自动回退时，要单独配置并验证故障处理策略。跨多个服务的标记传递可按 [逐跳验证](../../docs/components/traffic-routing.md#逐跳验证) 检查。
