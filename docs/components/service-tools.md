# 服务治理与可观测性组件

## Nacos

- **常用功能**：服务实例注册、健康实例发现和订阅，以及配置发布、读取与监听。服务按命名空间、分组和服务名组织，配置按命名空间、分组和 `dataId` 定位。
- **最小接入**：用对应版本的 Java SDK、框架集成或 API 连到 Nacos。注册测试服务的 IP 与端口并查询实例；用同一命名空间和分组发布一份测试配置，再读取并监听更新。实例类型、权限和客户端协议按实际版本配置。 [官方快速开始](https://nacos.io/en/docs/latest/quickstart/quick-start/)
- **配置灰度**。保留正式配置，在同一 namespace、group 和 dataId 下发布灰度版本。在测试环境填写一组 Beta 客户端 IP，或者按服务端与 SDK 版本使用 Tag 规则。用命中和未命中的客户端分别读取，检查内容、监听回调和真实生效值。通过后发布正式配置，或停止灰度再核对读取结果。显式请求 Tag 时，应按所用版本检查未匹配的响应。 [灰度规则文档](https://www.nacos.io/en/docs/next/manual/user/config/gray-release/) · [管理 API](https://nacos.io/en/docs/latest/manual/admin/admin-api/)
- **实例元数据**。注册同一服务的稳定实例和灰度实例，分别给 metadata 写入示例值 `version=stable` 与 `version=canary`，通过订阅或查询确认两个版本都被正确发现。调用方实际选择哪个实例取决于使用的客户端负载均衡或路由器。单独验证标签缺失、灰度实例下线和订阅更新，检查调用方是否按照所用路由规则选中目标版本。 [服务发现](https://nacos.io/en/docs/latest/manual/user/naming/overview/) · [订阅和推送](https://nacos.io/en/docs/latest/manual/user/naming/subscription-and-ops/)
- **验证**：下线测试实例，观察订阅者何时移除它。更新一项测试配置并观察通知与应用生效时间。断开客户端到服务端的连接，检查当前集成使用本地缓存还是启动失败。

请求标记、实例匹配和全链路验证见 [灰度路由组件](traffic-routing.md)。

## Sentinel

- **常用功能**：按资源限流、热点参数限流、熔断和系统保护。Sentinel 会根据规则拒绝进入资源的请求，框架适配器或调用方负责返回降级结果。
- **最小接入**：引入核心库或框架适配器，给接口命名为资源，例如 `inventory.lookup`，配置一条 QPS 流控规则。直接使用 Java API 时，用 `SphU.entry` 进入资源，处理 `BlockException`，并在 `finally` 中退出已获得的 `Entry`。 [资源与规则](https://sentinelguard.io/en-us/docs/basic-api-resource-rule.html)
- **验证**：以低于和高于阈值的请求调用同一资源，分别观察放行与拒绝。配置熔断时注入慢请求或异常，观察打开、探测恢复与关闭。单机限流与集群限流分别核对规则范围。 [熔断规则](https://sentinelguard.io/en-us/docs/circuit-breaking.html)

## Resilience4j

- **常用功能**。Java 调用侧可用 CircuitBreaker 按失败率或慢调用率熔断，用 Retry 配置有限重试，用 Bulkhead 限制并发，用 TimeLimiter 给异步调用设置等待上限。各实例按名称管理。单进程内的熔断状态和并发限制各自计算。 [CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker) · [Retry](https://resilience4j.readme.io/docs/retry) · [Bulkhead](https://resilience4j.readme.io/docs/bulkhead) · [TimeLimiter](https://resilience4j.readme.io/docs/timeout)
- **最小接入**。Spring Boot 3 项目可引入 `resilience4j-spring-boot3`、Actuator 和 Spring AOP。WebFlux 项目按实际使用情况加入 Reactor 模块。从另一个 Bean 调用被代理的 `@CircuitBreaker(name = "inventory")` 方法，用下方的同名实例先验证熔断。Java Core API 可用 `CircuitBreakerRegistry` 获取实例并包裹调用。 [Spring Boot 接入](https://resilience4j.readme.io/docs/getting-started-3) · [Java API](https://resilience4j.readme.io/docs/circuitbreaker)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      inventory:
        slidingWindowSize: 10
        minimumNumberOfCalls: 4
        failureRateThreshold: 50
        waitDurationInOpenState: 5s
        permittedNumberOfCallsInHalfOpenState: 2
```

- **运行边界**。熔断关注统计与阻断，限制并发要单独使用 Bulkhead。Retry 的 `maxAttempts` 包含首次调用。核对 HTTP 客户端是否也会重试，超时后还要观察实际 I/O 或异步任务是否停止。
- **验证**。让测试调用连续抛出被计入失败率的异常，记录实际下游请求数、熔断是否打开和后续请求是否被拒绝。恢复接口后等待开路窗口，再发请求验证半开试探和恢复。若启用重试，单独记录每次业务调用到达下游的次数。 [事件与指标](https://resilience4j.readme.io/docs/getting-started-3)

## Apache Seata

- **常用功能**：事务协调器管理全局事务。AT 依赖受支持的关系数据库、数据源代理和回滚日志。TCC 使用显式 Try/Confirm/Cancel，Saga 使用补偿，XA 依赖参与方支持 XA。
- **最小接入**：以 AT 为例，配置 Seata Server 和应用事务组，确认数据源与 SQL 在支持范围内。给各参与数据源创建对应版本的 `undo_log`，启用代理，在发起端使用 `@GlobalTransactional` 并验证 XID 传到参与方。 [官方快速开始](https://seata.apache.org/docs/user/quickstart/)
- **验证**：让最后一个参与方失败，核对前序变更、全局状态与回滚结果。检查全局锁冲突、事务超时和协调器重启。事务范围以登记的参与方为准，外部操作需要查对应的参与方式。 [事务模式与术语](https://seata.apache.org/docs/overview/what-is-seata/)

## OpenTelemetry

- **常用功能**：采集和导出追踪、指标、日志。可先用 Java Agent 自动接入常用框架，再按需用 API 增加业务 span 或指标。
- **最小接入**：启动应用时传入 `-javaagent`，设置 `OTEL_SERVICE_NAME`，选择 OTLP 导出协议和接收端。使用 Collector 时，配置 receiver、processor 和 exporter，并把它们接入对应信号的 pipeline。 [Java Agent 快速开始](https://opentelemetry.io/docs/zero-code/java/agent/getting-started/) · [Collector 配置](https://opentelemetry.io/docs/collector/configuration/)
- **验证**：发一次跨服务调用，检查上下游 trace 是否关联，检查对应指标和日志是否到达预定接收端。短暂关闭接收端，观察导出队列、重试和丢弃。采样和进程内缓冲会影响信号的完整性。

## Prometheus

- **常用功能**：抓取指标端点，用 PromQL 查询时间序列，定期执行记录规则或告警规则。
- **最小接入**：让应用暴露指标端点，例如 `/metrics`，向 `scrape_configs` 添加目标，配置实际路径。先查询 `up{job="demo"}` 确认抓取，再用 `rate(http_requests_total[5m])` 查看示例计数器的每秒速率。指标名和标签以端点输出为准。 [官方快速开始](https://prometheus.io/docs/prometheus/latest/getting_started/)
- **验证**：确认正常抓取时 `up` 为 1，停止端点后观察其变为 0 和告警的等待时长。核对实例重启时的计数器重置，并检查标签基数。请求 ID、用户 ID 等高基数字段宜放在日志或追踪中。 [指标命名与标签](https://prometheus.io/docs/practices/naming/)
