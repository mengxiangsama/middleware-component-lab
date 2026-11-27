# 服务治理与可观测性组件

## Nacos

- **常用功能**：服务实例注册、健康实例发现和订阅，以及配置发布、读取与监听。服务按命名空间、分组和服务名组织，配置按命名空间、分组和 `dataId` 定位。
- **最小接入**：用对应版本的 Java SDK、框架集成或 API 连到 Nacos。注册测试服务的 IP 与端口并查询实例；用同一命名空间和分组发布一份测试配置，再读取并监听更新。实例类型、权限和客户端协议按实际版本配置。 [官方快速开始](https://nacos.io/en/docs/latest/quickstart/quick-start/)
- **配置灰度**。保留正式配置，在同一 namespace、group 和 dataId 下发布灰度版本。在测试环境填写一组 Beta 客户端 IP，或者按服务端与 SDK 版本使用 Tag 规则。用命中和未命中的客户端分别读取，检查内容、监听回调和真实生效值。通过后发布正式配置，或停止灰度再核对读取结果。显式请求 Tag 时，应按所用版本检查未匹配的响应。 [灰度规则文档](https://www.nacos.io/en/docs/next/manual/user/config/gray-release/) · [管理 API](https://nacos.io/en/docs/latest/manual/admin/admin-api/)
- **实例元数据**。注册同一服务的稳定实例和灰度实例，分别给 metadata 写入示例值 `version=stable` 与 `version=canary`，通过订阅或查询确认两个版本都被正确发现。调用方实际选择哪个实例取决于使用的客户端负载均衡或路由器。单独验证标签缺失、灰度实例下线和订阅更新，检查调用方是否按照所用路由规则选中目标版本。 [服务发现](https://nacos.io/en/docs/latest/manual/user/naming/overview/) · [订阅和推送](https://nacos.io/en/docs/latest/manual/user/naming/subscription-and-ops/)
- **验证**：下线测试实例，观察订阅者何时移除它。更新一项测试配置并观察通知与应用生效时间。断开客户端到服务端的连接，检查当前集成使用本地缓存还是启动失败。

请求标记、实例匹配和全链路验证见 [灰度路由组件](traffic-routing.md)。

## Consul

Consul 让应用登记各个服务实例，并按健康状态发现可调用的地址。每个实例把服务名、ID 和检查配置注册到 Agent。调用方查询健康接口中的实例地址和端口，再向该地址发起业务请求。

- **最小接入**。通过 Agent 的注册接口，为同名实例分别设置 ID 和 HTTP 检查 URL。用 `GET /v1/health/service/<服务名>?passing=true` 取得健康实例。用无筛选的健康查询核对单个实例的状态。[注册接口](https://developer.hashicorp.com/consul/api-docs/agent/service#register-service) · [健康查询接口](https://developer.hashicorp.com/consul/api-docs/health#list-nodes-for-service)
- **联调验证**。运行 [Consul + HTTP 门店查询](../../labs/consul-health-discovery/README.md)，观察一个实例的健康端点出现 503 时如何退出发现结果，以及恢复后如何重新进入。

## etcd

- **常用功能**。etcd 是分布式键值存储。应用可用事务做条件更新、用 Watch 订阅变更，并用租约关联带有有效期的注册键。[etcd API](https://etcd.io/docs/v3.6/learning/api/)
- **最小接入**。连接 etcd 端点，用 Go 客户端的 `concurrency.NewSession` 创建会续约的会话。给同一个任务的候选进程使用相同 Election 前缀，用 `Campaign` 领取执行资格。[Election API](https://etcd.io/docs/v3.6/dev-guide/api_concurrency_reference_v3/)
- **验证**。运行 [租约选主和旧任务写入拦截](../../labs/etcd-leader-election/README.md)，核对候选排队、租约过期接手，以及 etcd 条件事务拦截过期执行者的写入。

## ZooKeeper

ZooKeeper 让分布式应用用带路径的 znode 保存小型协调数据。节点版本可用于条件修改，客户端可注册 Watch 感知变化。接入时选择业务专用路径，在两个客户端中分别写入和读取节点，核对版本的变化。Java 应用可搭配 [Apache Curator](#apache-curator) 管理会话和本地配置缓存。[ZooKeeper 开发文档](https://zookeeper.apache.org/doc/r3.9.3/zookeeperProgrammers.html)

## Apache Curator

Apache Curator 是 [ZooKeeper](#zookeeper) 的 Java 客户端，提供连接管理和缓存等常用用法。使用 `curator-recipes` 创建 `CuratorFramework` 并连接集群，再用 `CuratorCache` 观察节点。在 [门店配置实验](../../labs/curator-zookeeper-cache/README.md) 中，后台修改和删除节点后，应用核对本地值和删除事件。[官方接入示例](https://curator.apache.org/docs/getting-started/) · [CuratorCache](https://curator.apache.org/docs/recipes-curator-cache/)

## Sentinel

仓库的 Maven 配置、容器镜像和已有实验未固定 Sentinel 版本。本例使用 Java 17，选用 `com.alibaba.csp:sentinel-core:1.8.9` 和同版本的 `sentinel-parameter-flow-control`。规则和计数由应用进程内的库处理，实验无需启动控制台或集群 Token Server。 [Sentinel 1.8.9 发布记录](https://github.com/alibaba/Sentinel/releases/tag/1.8.9)

| 主要功能 | 状态 | 用途 |
| --- | --- | --- |
| 资源限流 | 待补 | 按资源的 QPS 或并发线程数限制请求。 |
| 热点参数限流 | 已有教程 | [按商品 ID 限制库存查询](#按商品-id-限制库存查询)。 |
| 熔断 | 待补 | 按慢调用比例、异常比例或异常数暂时阻断调用。 |
| 系统自适应保护 | 待补 | 按实例负载、CPU 或入口流量等信号保护应用。 |
| 集群流控 | 待补 | 通过 Token Server 协调多个实例的配额。 |
| 网关流控 | 待补 | 根据路由或自定义 API 分组限制入口流量。 |
| 控制台与动态规则 | 待补 | 查看应用指标，结合规则数据源分发或持久化规则。 |

### 按商品 ID 限制库存查询

**适用场景**　促销期间，某个商品的库存查询集中涌入。所有查询走同一个资源，按商品 ID 计量，使单个商品耗尽配额后，其他商品仍可查询。此规则适用于能从每次调用稳定提取商品 ID 的单实例服务。多实例使用本地规则时各自计量，整个服务共用一个配额时需配置集群流控。多个商品仍可能同时争用下游容量，有总量约束时还需为整个资源设置限额。

**接入步骤**　Maven 应用引入 `com.alibaba.csp:sentinel-parameter-flow-control:1.8.9`，它会带入同版本的核心库。参照 [ProductHotspotDemo.java](../../labs/sentinel/ProductHotspotDemo.java)，加载名为 `inventory.lookup` 的热点规则，令 `paramIdx=0` 对应传给 `SphU.entry` 的第一个业务参数。默认采用 QPS 模式和超额立即拒绝策略。本例把统计时长设为 60 秒，普通商品的配额为 1 次，`sku-featured` 的配额为 2 次，便于在一次短运行里观察耗尽。例外项用 `String.class.getName()` 对应实际传入的字符串类型。

每次查询在执行业务前调用 `SphU.entry(RESOURCE, EntryType.IN, 1, productId)`，成功获得 `Entry` 后在 `finally` 中用 `entry.exit(1, productId)` 退出。拦截时示例捕获 `ParamFlowException` 并输出 `BLOCK`，HTTP 接口可以据此返回 429。接入时先校验商品 ID，缺少索引对应的参数或传入 `null` 时，该热点规则会跳过检查。

**验证方法**　从仓库根目录运行。需要 Bash 或 Zsh、JDK 17、支持 `--retry-all-errors` 的 curl（7.71.0+）和 Maven Central 的网络连接。示例直接运行 Java 源文件，依赖和 Sentinel 日志写入临时目录，退出时清理。

```bash
(
  set -eu
  lab_dir=$(mktemp -d "${TMPDIR:-/tmp}/sentinel-hotspot.XXXXXX")
  trap 'rm -rf "$lab_dir"' EXIT

  central=https://repo.maven.apache.org/maven2
  for module in sentinel-core sentinel-parameter-flow-control; do
    curl -fsSL --retry 3 --retry-all-errors --max-time 30 \
      "$central/com/alibaba/csp/$module/1.8.9/$module-1.8.9.jar" \
      -o "$lab_dir/$module.jar"
  done
  curl -fsSL --retry 3 --retry-all-errors --max-time 30 \
    "$central/com/googlecode/concurrentlinkedhashmap/concurrentlinkedhashmap-lru/1.4.2/concurrentlinkedhashmap-lru-1.4.2.jar" \
    -o "$lab_dir/concurrentlinkedhashmap-lru.jar"

  java -Duser.home="$lab_dir" -cp "$lab_dir/*" labs/sentinel/ProductHotspotDemo.java
)
```

除 Sentinel 启动日志外，预期按顺序输出下列结果。示例逐次断言是否放行，任一结果不符即以非零状态退出。

```text
sku-hot PASS
sku-hot BLOCK
sku-other PASS
sku-featured PASS
sku-featured PASS
sku-featured BLOCK
```

**实测记录**　在 macOS 26.5（arm64）、Microsoft OpenJDK 17.0.16 和 curl 8.7.1 下用 Bash 运行上述命令。依赖下载遇到 TLS 错误后由 curl 退避重试成功，Java 进程退出码为 0，六次调用依次得到上述结果，临时依赖和日志目录随命令退出清理。

**官方文档**　[热点参数限流](https://sentinelguard.io/en-us/docs/parameter-flow-control.html)说明依赖、参数位置和例外项。[资源与规则](https://sentinelguard.io/en-us/docs/basic-api-resource-rule.html)说明如何进入和退出资源。[1.8.9 规则定义](https://raw.githubusercontent.com/alibaba/Sentinel/1.8.9/sentinel-extension/sentinel-parameter-flow-control/src/main/java/com/alibaba/csp/sentinel/slots/block/flow/param/ParamFlowRule.java)与 [1.8.9 检查逻辑](https://raw.githubusercontent.com/alibaba/Sentinel/1.8.9/sentinel-extension/sentinel-parameter-flow-control/src/main/java/com/alibaba/csp/sentinel/slots/block/flow/param/ParamFlowChecker.java)对应本例的统计时长、配额及参数校验行为。

## Resilience4j

Resilience4j 是嵌入 JVM 的调用侧库，无独立服务端或容器。仓库根 POM、Spring Modulith 实验和 Compose 均未配置 Resilience4j 运行依赖或镜像。本节选用 Resilience4j 2.3.0 和 Java 17。 [官方 2.3.0 发布记录](https://github.com/resilience4j/resilience4j/releases/tag/v2.3.0) · [2.3.0 模块与示例](https://github.com/resilience4j/resilience4j/tree/v2.3.0)

| 主要功能 | 状态 | 用途 |
| --- | --- | --- |
| Retry 重试 | 已有教程 | [按异常类型有限重试](#按异常类型有限重试) |
| CircuitBreaker 熔断 | 已有教程 | [搭配 Retry 保护库存 HTTP 查询](../../labs/resilience4j-inventory/README.md)。 |
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

需要同时处理短暂 503 和持续故障时，运行 [Resilience4j + Java HttpClient 库存实验](../../labs/resilience4j-inventory/README.md)。Retry 在外层决定是否安排下一次尝试，CircuitBreaker 在内层决定是否允许发送 HTTP 请求。实验直接在库存 HTTP 服务端核对请求次数。

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

## Toxiproxy

Toxiproxy 在测试环境转发 TCP 连接，通过控制 API 动态添加或删除延迟等故障。创建代理后，将被测客户端的目标改为代理的监听地址，连接、请求和后端仍使用各自的协议。可以分别控制上行和下行。控制 API 应仅供实验操作访问。[代理和 toxic 配置](https://github.com/Shopify/toxiproxy/tree/v2.13.0#toxics)

运行 [Toxiproxy + Java HttpClient](../../labs/toxiproxy-http-timeout/README.md)，核对正常库存查询、仅增加响应延迟时的请求超时，以及移除延迟后恢复。实验用后端请求数确认超时的请求确实到达过后端。

## Apache Seata

- **常用功能**：事务协调器管理全局事务。AT 依赖受支持的关系数据库、数据源代理和回滚日志。TCC 使用显式 Try/Confirm/Cancel，Saga 使用补偿，XA 依赖参与方支持 XA。
- **最小接入**：以 AT 为例，配置 Seata Server 和应用事务组，确认数据源与 SQL 在支持范围内。给各参与数据源创建对应版本的 `undo_log`，启用代理，在发起端使用 `@GlobalTransactional` 并验证 XID 传到参与方。 [官方快速开始](https://seata.apache.org/docs/user/quickstart/)
- **验证**：让最后一个参与方失败，核对前序变更、全局状态与回滚结果。检查全局锁冲突、事务超时和协调器重启。事务范围以登记的参与方为准，外部操作需要查对应的参与方式。 [事务模式与术语](https://seata.apache.org/docs/overview/what-is-seata/)

## Open Policy Agent

OPA 用 Rego 根据输入求值。HTTP 网关在认证并查询业务数据后，将操作、角色和资源归属交给 OPA 的 Data API，再按返回的决策放行或拒绝。把策略与处理业务请求的代码分开，便于直接测试同仓库、跨仓库及默认拒绝的输入。[HTTP API 授权示例](https://www.openpolicyagent.org/docs/http-api-authorization) · [Data API](https://www.openpolicyagent.org/docs/rest-api#get-a-document-with-input)

运行 [OPA + Python 网关实验](../../labs/opa-shipment-authorization/README.md)，核对身份缺失、授权拒绝和策略服务不可用时的 HTTP 响应。

## OpenTelemetry

- **常用功能**：采集和导出追踪、指标、日志。可先用 Java Agent 自动接入常用框架，再按需用 API 增加业务 span 或指标。
- **最小接入**：启动应用时传入 `-javaagent`，设置 `OTEL_SERVICE_NAME`，选择 OTLP 导出协议和接收端。使用 Collector 时，配置 receiver、processor 和 exporter，并把它们接入对应信号的 pipeline。 [Java Agent 快速开始](https://opentelemetry.io/docs/zero-code/java/agent/getting-started/) · [Collector 配置](https://opentelemetry.io/docs/collector/configuration/)
- **验证**：发一次跨服务调用，检查上下游 trace 是否关联，检查对应指标和日志是否到达预定接收端。短暂关闭接收端，观察导出队列、重试和丢弃。采样和进程内缓冲会影响信号的完整性。

用 JDK `HttpClient` 调库存且需要手动接入追踪时，在发出请求前由 Java SDK 将 CLIENT span 的上下文写入 `traceparent`，接收端提取后建 SERVER span。 [跨 HTTP 传播实验](../../labs/otel-java-http-propagation/README.md)核对实际请求头、两侧 Trace ID、父 span ID，以及请求头缺失或无效时的行为。

[Collector 搭配 Grafana Tempo 实验](../../labs/otel-collector-tempo/README.md)实测 OTLP/HTTP 接收、OTLP/gRPC 导出，以及按 Trace ID 读回父子 span。

## Micrometer

Micrometer 在 Java 应用内记录指标，通过 `MeterRegistry` 将计数、计时和状态交给不同的监控后端。Spring Boot 自动配置注册表。接入时可用 `Counter` 记录发生次数，用 `Timer` 同时记录操作次数、累计耗时和可选的直方图桶。检查结果可以作标签，订单或用户 ID 适合保留在日志和追踪中。[Micrometer 概念](https://docs.micrometer.io/micrometer/reference/concepts.html) · [Spring Boot 指标配置](https://docs.spring.io/spring-boot/3.5/reference/actuator/metrics.html)

运行 [Actuator + Prometheus 发货耗时实验](../../labs/micrometer-actuator-timer/README.md)，核对通过、拒绝及超过 50 毫秒的检查次数。

## Prometheus

采集 Spring Boot 应用的 JVM 指标，见 [Actuator 搭配 Prometheus](release-tools.md#搭配-prometheus-采集应用指标)。下方告警规则实验使用官方镜像 `prom/prometheus:v3.16.0` 中的 `promtool` 直接注入样本，无需启动应用或 Prometheus 服务端。[3.16.0 发布记录](https://github.com/prometheus/prometheus/releases/tag/v3.16.0)

| 3.16.0 主要功能 | 教程状态 |
| --- | --- |
| 指标抓取、目标发现与重标记 | **联调实测** · [抓取 Actuator 静态目标](../../labs/micrometer-actuator-timer/README.md)。服务发现与重标记见 [配置参考](https://prometheus.io/docs/prometheus/latest/configuration/configuration/)。 |
| PromQL 与 HTTP 查询 API | **联调实测** · [查询 Timer 累计次数和耗时桶](../../labs/micrometer-actuator-timer/README.md#从指标回答业务问题)。 |
| 记录规则 | 待补。定期计算表达式并将结果写成新的时间序列。 |
| 告警规则与 Alertmanager 对接 | **联调实测**。[合并订单服务触发与恢复通知](../../labs/alertmanager-webhook/README.md)。 |
| 规则语法检查与告警单元测试 | 已有教程。[测试订单服务抓取失败告警](#测试订单服务抓取失败告警) |
| 本地时序存储、远程读写与联邦 | 待补。按部署需求设置本地保留策略、对接远程存储或从其他 Prometheus 抓取选定序列。 |

### 测试订单服务抓取失败告警

**适用场景**　订单服务偶发一次抓取失败时，希望等失败持续两分钟再触发告警。上线规则前，用固定样本检查等待时间、恢复行为以及告警标签和文案。这里的 `up` 表示 Prometheus 对已配置目标的抓取结果。目标从服务发现中消失时，序列可能缺失，需要单独设计缺失检测。

**接入步骤**　准备可运行 Linux 容器的 Docker。从仓库根目录使用 [规则文件](../../labs/prometheus-rules/alerts.yml)和[测试文件](../../labs/prometheus-rules/alerts.test.yml)。规则选择 `job="orders"` 的实例，`up == 0` 持续满足 `for: 2m` 后进入 firing。测试每分钟提供一个样本并评估一次规则。接入运行中的 3.16.0 服务端时，将规则文件加入 `prometheus.yml` 的 `rule_files`，并确认抓取任务名和评估间隔符合预期。

**验证方法**　从仓库根目录运行以下命令。首次运行时 Docker 需要取得指定镜像。规则目录以只读方式挂载，容器内无需网络、Prometheus 服务进程或 Alertmanager。

```bash
docker run --rm --network none --entrypoint /bin/promtool \
  --mount "type=bind,source=$PWD/labs/prometheus-rules,target=/lab,readonly" \
  --workdir /lab prom/prometheus:v3.16.0 check rules alerts.yml

docker run --rm --network none --entrypoint /bin/promtool \
  --mount "type=bind,source=$PWD/labs/prometheus-rules,target=/lab,readonly" \
  --workdir /lab prom/prometheus:v3.16.0 test rules alerts.test.yml
```

| 模拟时间 | `up` | 预期 |
| --- | --- | --- |
| 0m | 1 | 抓取正常，无 firing 告警 |
| 1m、2m | 0 | 等待持续时间达标，无 firing 告警 |
| 3m | 0 | 一个 firing 告警，匹配测试文件中的实例、级别和摘要 |
| 4m | 1 | 抓取恢复，无 firing 告警 |

`alert_rule_test` 断言 firing 告警。空列表表示当时没有 firing 告警，等待阶段的断言可发现提前触发。通知路由和接收端送达需在 Alertmanager 中另行验证。

**实测记录**　2026-11-23 在 macOS arm64、Docker Engine 27.3.1 上使用 Linux arm64 镜像运行上述两条命令，实测命令增加了 `--pull=never` 以复用本机镜像。两条命令均以状态码 0 退出，语法检查显示 `SUCCESS: 1 rules found`，规则测试显示 `SUCCESS`，五个时间点的断言全部通过。

**官方文档**　[3.16.0 告警规则](https://github.com/prometheus/prometheus/blob/v3.16.0/docs/configuration/alerting_rules.md) · [3.16.0 规则单元测试](https://github.com/prometheus/prometheus/blob/v3.16.0/docs/configuration/unit_testing_rules.md)

**扩展验证**　[完整实验文档](../../labs/prometheus-rules/README.md)补充了短暂故障不触发、中途恢复后重新等待，以及健康实例和其他 job 的筛选对照。测试还查询 `ALERTS`，明确断言 `2m` pending、`3m` firing、`4m` 解除。运行 `sh labs/prometheus-rules/verify.sh` 可一次完成版本输出、规则检查和三组测试，CI 使用同一入口。
