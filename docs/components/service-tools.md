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

Resilience4j 是嵌入 JVM 的调用侧库，无独立服务端或容器。仓库根 POM、Spring Modulith 实验和 Compose 均未配置 Resilience4j 运行依赖或镜像。本节选用 Resilience4j 2.3.0 和 Java 17。 [官方 2.3.0 发布记录](https://github.com/resilience4j/resilience4j/releases/tag/v2.3.0) · [2.3.0 模块与示例](https://github.com/resilience4j/resilience4j/tree/v2.3.0)

| 主要功能 | 状态 | 用途 |
| --- | --- | --- |
| Retry 重试 | 已有教程 | [按异常类型有限重试](#按异常类型有限重试) |
| CircuitBreaker 熔断 | 待补 | 按失败率或慢调用率阻断调用，半开时试探恢复。 |
| RateLimiter 限流 | 待补 | 按周期分配调用许可。 |
| Bulkhead 并发隔离 | 已有教程 | [用信号量限制库存查询的并发](#用信号量限制库存查询的并发)。有界线程池隔离待补。 |
| TimeLimiter 异步超时 | 待补 | 为异步结果设置等待上限。 |
| Cache 缓存装饰器 | 待补 | 用 JCache 缓存函数结果。 |

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

### 按异常类型有限重试

**适用场景**。库存查询遇到暂时不可用时，可以在调用方的总超时预算内少量重试。只重试明确可恢复的故障，参数错误直接返回。若用于写操作，先确保有业务幂等保障，并核对 HTTP 客户端自身的重试次数，避免两层重试放大流量。

**接入步骤**。使用 JDK 17、curl 和可访问 Maven Central 的网络。在同一个 Bash 会话中执行以下命令，依赖与 Java 源文件放在独立临时目录，退出会话时清理。实际应用可直接声明 `io.github.resilience4j:resilience4j-retry:2.3.0`，由构建工具解析传递依赖。示例使用 SLF4J NOP 绑定，输出来自示例自身。

```bash
retry_demo_dir=$(mktemp -d)
trap 'rm -rf -- "$retry_demo_dir"' EXIT

(
  set -e
  cd "$retry_demo_dir"
  central=https://repo1.maven.org/maven2
  for module in retry core; do
    jar="resilience4j-${module}-2.3.0.jar"
    curl -fsSL --retry 3 -o "$jar" "$central/io/github/resilience4j/resilience4j-$module/2.3.0/$jar"
  done
  for module in api nop; do
    jar="slf4j-${module}-1.7.30.jar"
    curl -fsSL --retry 3 -o "$jar" "$central/org/slf4j/slf4j-$module/1.7.30/$jar"
  done
)
```

将下面的代码保存为 `$retry_demo_dir/RetryDemo.java`。通过 `retryExceptions` 限定可重试异常，`maxAttempts(3)` 把首次调用也计入上限。同步调用会在两次尝试之间等待，本例配置固定间隔 50 毫秒。

```java
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

public class RetryDemo {
    static class InventoryUnavailable extends RuntimeException {}

    public static void main(String[] args) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(50))
                .retryExceptions(InventoryUnavailable.class)
                .build();
        Retry retry = Retry.of("inventory", config);

        check(retry, "recover", attempt -> {
            if (attempt < 3) {
                throw new InventoryUnavailable();
            }
            return "in-stock";
        }, 3, "in-stock");
        check(retry, "exhaust", attempt -> {
            throw new InventoryUnavailable();
        }, 3, "InventoryUnavailable");
        check(retry, "invalid", attempt -> {
            throw new IllegalArgumentException("missing SKU");
        }, 1, "IllegalArgumentException");
    }

    private static void check(Retry retry, String scenario, IntFunction<String> inventory,
                              int expectedAttempts, String expectedOutcome) {
        AtomicInteger attempts = new AtomicInteger();
        String outcome;
        try {
            outcome = retry.executeSupplier(() -> inventory.apply(attempts.incrementAndGet()));
        } catch (InventoryUnavailable | IllegalArgumentException e) {
            outcome = e.getClass().getSimpleName();
        }
        if (attempts.get() != expectedAttempts || !expectedOutcome.equals(outcome)) {
            throw new AssertionError(scenario + ": attempts=" + attempts + ", outcome=" + outcome);
        }
        System.out.printf("%s attempts=%d outcome=%s%n", scenario, attempts.get(), outcome);
    }
}
```

**验证**。运行 Java 源文件。退出码应为 0，三个场景分别显示第三次调用成功、三次调用后将最后的异常交回调用方、参数错误在第一次调用时立即返回。示例会对调用次数和结果进行断言。

```bash
(cd "$retry_demo_dir" && java --class-path '*' RetryDemo.java)
```

2026-11-23 在 macOS 26.5（arm64）、Microsoft OpenJDK 17.0.16 上，按上述命令下载依赖并运行，退出码为 0，实际输出如下。

```text
recover attempts=3 outcome=in-stock
exhaust attempts=3 outcome=InventoryUnavailable
invalid attempts=1 outcome=IllegalArgumentException
```

**官方文档**。 [Retry 配置与 Java 用法](https://resilience4j.readme.io/docs/retry) · [2.3.0 RetryConfig 源码](https://raw.githubusercontent.com/resilience4j/resilience4j/v2.3.0/resilience4j-retry/src/main/java/io/github/resilience4j/retry/RetryConfig.java)

### 用信号量限制库存查询的并发

**适用场景**。库存接口变慢时，应用中等待库存结果的请求会增多。同步调用方可以用一个共享的信号量 Bulkhead 限制单个 JVM 内同时执行的库存查询数，满载时立即交由调用方处理。信号量适用于已有调用线程的场景，受保护的查询在调用线程上执行。应用有多个副本时，每个副本独立计算许可。

**接入步骤**。使用 JDK 17、Bash、curl 和可访问 Maven Central 的网络。应用可通过构建工具声明 `io.github.resilience4j:resilience4j-bulkhead:2.3.0`。下面的独立示例在同一个 Bash 会话中运行，下载本例用到的 JAR 到临时目录，退出会话时清理。

```bash
bulkhead_demo_dir=$(mktemp -d)
trap 'rm -rf -- "$bulkhead_demo_dir"' EXIT

(
  set -e
  cd "$bulkhead_demo_dir"
  central=https://repo1.maven.org/maven2
  for module in bulkhead core; do
    jar="resilience4j-${module}-2.3.0.jar"
    curl -fsSL --retry 3 -o "$jar" "$central/io/github/resilience4j/resilience4j-$module/2.3.0/$jar"
  done
  for module in api nop; do
    jar="slf4j-${module}-1.7.30.jar"
    curl -fsSL --retry 3 -o "$jar" "$central/org/slf4j/slf4j-$module/1.7.30/$jar"
  done
)
```

将下面的代码保存为 `$bulkhead_demo_dir/BulkheadDemo.java`。`maxConcurrentCalls(1)` 允许一个在途调用，`maxWaitDuration(Duration.ZERO)` 让满载时的后续调用立即收到 `BulkheadFullException`。对同一库存接口复用一个 Bulkhead 实例。示例用两个锁存器控制调用重叠，不依赖休眠来制造满载。

```java
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class BulkheadDemo {
    public static void main(String[] args) throws Exception {
        BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ZERO)
                .build();
        Bulkhead inventory = Bulkhead.of("inventory", config);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();

        try {
            var first = worker.submit(() -> inventory.executeCallable(() -> {
                entered.countDown();
                if (!finish.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("first call timed out waiting for release");
                }
                return "in-stock";
            }));
            if (!entered.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("first call did not enter");
            }

            try {
                inventory.executeRunnable(() -> {
                    throw new AssertionError("second call reached inventory");
                });
                throw new AssertionError("second call was not rejected");
            } catch (BulkheadFullException expected) {
                System.out.println("second=BulkheadFullException");
            }

            finish.countDown();
            String firstResult = first.get(5, TimeUnit.SECONDS);
            String thirdResult = inventory.executeSupplier(() -> "in-stock");
            if (!"in-stock".equals(firstResult) || !"in-stock".equals(thirdResult)) {
                throw new AssertionError("unexpected inventory result");
            }
            System.out.printf("first=%s third=%s%n", firstResult, thirdResult);
        } finally {
            finish.countDown();
            worker.shutdownNow();
        }
    }
}
```

**验证**。在同一个 Bash 会话中运行以下命令。预期退出码为 0。第二个调用收到异常且未执行查询，首个调用完成后，第三个调用使用释放的许可正常返回。

```bash
(cd "$bulkhead_demo_dir" && java --class-path '*' BulkheadDemo.java)
```

2026-11-23 在 macOS 26.5（arm64）、Microsoft OpenJDK 17.0.16 上，按上述命令下载依赖并运行，退出码为 0，实际输出如下。

```text
second=BulkheadFullException
first=in-stock third=in-stock
```

**官方文档**。 [Bulkhead 配置与 Java 用法](https://resilience4j.readme.io/docs/bulkhead) · [2.3.0 Bulkhead API](https://raw.githubusercontent.com/resilience4j/resilience4j/v2.3.0/resilience4j-bulkhead/src/main/java/io/github/resilience4j/bulkhead/Bulkhead.java) · [2.3.0 BulkheadConfig](https://raw.githubusercontent.com/resilience4j/resilience4j/v2.3.0/resilience4j-bulkhead/src/main/java/io/github/resilience4j/bulkhead/BulkheadConfig.java)

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
