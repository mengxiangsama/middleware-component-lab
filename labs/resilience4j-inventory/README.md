# Resilience4j Retry + CircuitBreaker 保护库存 HTTP 查询

## 适用场景

订单页调用库存服务查余量时，偶发 HTTP 503 可以少量重试。库存服务持续返回 503 时，来自每个订单页的重试会增加下游压力。用 CircuitBreaker 拒绝一段时间内的新调用，等下游恢复后再放行少量请求试探。

| 组件 | 分工 |
| --- | --- |
| JDK `HttpClient` | 请求库存 HTTP 接口，设置连接和单次请求超时，将 HTTP 503 转成 `InventoryUnavailable` 异常。 |
| Resilience4j Retry | 只对 `InventoryUnavailable` 安排后续尝试，一次业务调用最多 3 次尝试。 |
| Resilience4j CircuitBreaker | 统计每次获准的下游尝试。连续两次失败后打开，等待窗口结束后放行两次半开试探。 |

## 接入步骤

1. 项目引入 `io.github.resilience4j:resilience4j-retry:2.3.0` 和 `io.github.resilience4j:resilience4j-circuitbreaker:2.3.0`。为库存下游创建并复用同一个 `Retry` 和 `CircuitBreaker` 实例。应用副本各自维护熔断状态。
2. 在 HTTP 响应处理层将可重试的 503 映射为 `InventoryUnavailable`，并在 `RetryConfig.retryExceptions(...)` 中登记这种异常。打开时，熔断器抛出 `CallNotPermittedException`，这项配置会让 Retry 立即把它交还给调用方。
3. 先用熔断器包装 HTTP 调用，再在外层加 Retry。每次尝试都先取得熔断许可，再发 HTTP 请求。按下方顺序包装后，单次业务请求也可能累积多次下游失败。

```java
CheckedSupplier<Integer> protectedStock = Retry.decorateCheckedSupplier(retry,
        CircuitBreaker.decorateCheckedSupplier(breaker, stockOverHttp));
int quantity = protectedStock.get();
```

示例用大小为 2 的计数滑动窗口，要求至少 2 次调用，失败率达到 100% 时打开。接到真实库存服务时，按接口流量选择窗口和失败阈值，并把尝试次数、每次 HTTP 超时和等待间隔一起计入端到端超时预算。

## 本地验证

从仓库根目录运行。要求 JDK 17+、`curl` 和可访问 Maven Central 的网络。脚本把依赖放到临时目录，在 `127.0.0.1` 的随机端口启动一个库存 HTTP 接口，并在退出时清理。

```sh
sh labs/resilience4j-inventory/verify.sh
```

脚本在 HTTP 服务端计数，并断言重试事件、熔断状态和拒绝次数。预期输出如下。

```text
OPEN: HTTP calls=2; third attempt blocked
OPEN: next request rejected once; HTTP calls still 2
HALF_OPEN: first probe returned 42
CLOSED: second probe returned 42; total HTTP calls=4
```

前两次 HTTP 请求都返回 503。第二次失败打开熔断，已安排的第三次尝试被阻止。再发一笔独立查询，调用方收到一次拒绝，下游调用计数和 Retry 重试计数保持不变。随后让库存接口返回 200，并推进实验时钟越过 5 秒等待窗口。第一笔成功查询进入 `HALF_OPEN`，第二笔成功查询回到 `CLOSED`。

## 官方文档

- [CircuitBreaker 状态、滑动窗口与阈值](https://resilience4j.readme.io/docs/circuitbreaker)
- [Retry 配置与异常分类](https://resilience4j.readme.io/docs/retry)
- [Java 包装示例](https://resilience4j.readme.io/docs/examples) · [2.3.0 发布与源码](https://github.com/resilience4j/resilience4j/releases/tag/v2.3.0)
- [Spring Boot 注解顺序](https://resilience4j.readme.io/docs/getting-started-3#aspect-order)
