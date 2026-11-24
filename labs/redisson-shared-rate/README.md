# Redisson + Valkey 跨实例共享报价接口额度

## 适用场景

两个报价服务实例都调用同一个供应商接口，而供应商给整个应用分配一份调用额度。用同名限流器共享额度，避免每个应用实例分别消耗一份预算。

| 组件 | 分工 |
| --- | --- |
| [Redisson](../../docs/components/search-cache.md#redisson) | 通过 `RRateLimiter` 初始化策略，并在发出报价请求前尝试取得许可。 |
| [Valkey](../../docs/components/search-cache.md#valkey) | 保存限流器的配置和已用额度，让不同 JVM 的 Redisson 客户端读写同一份状态。 |

## 接入步骤

1. 各实例使用同一个 Valkey 部署、逻辑库和限流器名称。示例连接本机 Valkey，使用 `quote-api:global`。
2. 启动时用 `trySetRate(RateType.OVERALL, 2, Duration.ofHours(1))` 初始化。这里用较长间隔观察本轮额度用尽。已有配置时 `trySetRate` 返回 `false`，已设的额度仍生效。
3. 调供应商接口前调用 `tryAcquire()`。取得许可才发出请求，返回 `false` 时由调用方处理限流结果，例如返回 429。

```java
RRateLimiter limiter = client.getRateLimiter("quote-api:global");
limiter.trySetRate(RateType.OVERALL, 2, Duration.ofHours(1));

if (limiter.tryAcquire()) {
    // Send the vendor quote request.
}
```

各应用通过相同限流器名称共享额度。Redisson 客户端由应用复用，退出时关闭。

## 本地验证

需要 JDK 17+ 和 Valkey 8.1.12 的 `valkey-server`。Apple Silicon 上构建二进制的命令见 [Valkey + Lettuce 实验](../valkey-client-cache/README.md#验证方法)。从仓库根目录运行以下命令并替换二进制路径。

```sh
VALKEY_SERVER=/absolute/path/to/valkey-server ./mvnw -B -f labs/redisson-shared-rate/pom.xml clean verify
```

测试启动临时 Valkey 实例，仅监听回环地址，使用两个独立 JVM 访问。结束时关闭进程并清理临时文件。

| 操作 | 结果 |
| --- | --- |
| JVM A 配置总额度为 2 并尝试获取 | 成功，剩余额度为 1 |
| JVM B 尝试用 99 覆盖已设额度，再尝试获取 | 初始化返回 `false`，获取第二个许可成功 |
| JVM A 再次尝试获取 | 返回 `false`，全局剩余额度为 0 |

实验覆盖同名限流器的初始化与跨 JVM 扣减。额度恢复时间、HTTP 返回码和节点切换由各自的接入场景另行验证。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、JDK 17.0.16、Redisson 4.8.0 和 Valkey 8.1.12 上运行上述命令，1 项集成测试通过。A、B 分别取得一个许可，A 继续申请被拒绝，剩余额度为 0。

## 官方资料

- [Redisson 4.8.0](https://github.com/redisson/redisson/releases/tag/redisson-4.8.0) · [RRateLimiter 示例与配置](https://redisson.pro/docs/data-and-services/objects/#ratelimiter)
- [Valkey 8.1.12](https://github.com/valkey-io/valkey/releases/tag/8.1.12)
