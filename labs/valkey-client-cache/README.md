# Valkey + Lettuce 客户端价格缓存

当同一商品价格被频繁读取且改价较少时，应用可以用本地缓存减少对 Valkey 的重复读取。后台改价后，Valkey 向追踪该键的读取连接发送失效通知。读取端删除对应的本地副本，下次查询从 Valkey 读新价格。

| 组件 | 分工 |
| --- | --- |
| [Valkey](../../docs/components/search-cache.md#valkey) | 存储价格。启用 `CLIENT TRACKING ON` 后，按连接追踪读过的键，并在键变化时推送失效。 |
| [Lettuce](../../docs/components/search-cache.md#lettuce) | 用同一条 RESP3 连接执行读取和接收失效通知，由 `ClientSideCaching` 查本地缓存、回源并清除失效条目。 |
| 应用本地缓存 | 保存经常读取的价格。实验用 `ConcurrentHashMap`，接入业务时需要容量和 TTL 策略。 |

## 接入步骤

1. 为读端配置 Lettuce 的 `ClientOptions.protocolVersion(ProtocolVersion.RESP3)`，创建专用连接。用 `ClientSideCaching.enable(CacheAccessor.forMap(prices), reader, TrackingArgs.Builder.enabled())` 启用 tracker。关闭 `CacheFrontend` 时会关闭读连接。
2. 商品查询调用 `frontend.get(skuKey)`。首次查询发送 `GET` 并在本地缓存结果，同一键的后续查询可以直接读取本地副本。
3. 改价方用另一条连接执行 `SET`。Valkey 在原读取连接发送 `invalidate` push，Lettuce 清除对应键。下一次读通过 `GET` 加载新价格。

写入返回 `OK` 后，其他 JVM 仍需时间处理失效。生产接入要处理断线清空本地缓存和重连重新追踪，并为读取结果设定可接受的陈旧时间。对成交价等强一致读，从业务权威数据源重新校验。实验验证单节点、顺序读写和指定键失效，断线重连、并发竞争和集群拓扑需另行验证。

## 验证方法

准备 Java 17 和 Valkey 8.1.12 的 `valkey-server`。从源代码构建时，可下载官方 [8.1.12 源码](https://github.com/valkey-io/valkey/releases/tag/8.1.12)，在解压后的目录执行以下命令。

```sh
make -C src -j3 MALLOC=libc BUILD_TLS=no OPT=-O1 valkey-server
```

从本仓库根目录执行。测试启动临时 Valkey，仅监听回环地址，使用临时数据目录，并在结束时关闭服务。

```sh
VALKEY_SERVER=/absolute/path/to/valkey-server ./mvnw -B -f labs/valkey-client-cache/pom.xml verify
```

| 操作 | 观测 |
| --- | --- |
| 查询两个商品价格 | `INFO commandstats` 中的 `GET` 次数增加 2。 |
| 再次查询已缓存的商品 | 返回价格 19900 分，`GET` 次数不变。 |
| 另一条客户端连接将此商品改到 21900 分 | 只有改价键从本地移除，再次读取返回 21900 分，`GET` 次数增加 1。另一件商品保持缓存命中。 |

## 实测记录

2026-11-24 在 macOS 26.5 arm64、JDK 17.0.16、Valkey 8.1.12、Lettuce 6.6.0.RELEASE 下执行上述命令。1 项集成测试通过，三阶段的 `GET` 增量分别为 2、0、1。

## 官方资料

- [Valkey 客户端缓存](https://valkey.io/topics/client-side-caching/) · [CLIENT TRACKING](https://valkey.io/commands/client-tracking/) · [INFO](https://valkey.io/commands/info/)
- [Lettuce 连接选项](https://redis.github.io/lettuce/advanced-usage/client-options/) · [ClientSideCaching 6.6.0.RELEASE 源码示例](https://github.com/redis/lettuce/blob/6.6.0.RELEASE/src/main/java/io/lettuce/core/support/caching/ClientSideCaching.java)
