# Spring Cache 搭配 Caffeine 处理商品改价

## 解决什么问题

商品详情高频读取时，用进程内缓存减少数据库查询。后台改价成功后，下一次详情读取应拿到新价。若改价事务回滚，原价仍有效，缓存也可以继续使用。

- Spring Cache 在服务方法上定义读缓存和失效的规则。
- Caffeine 在当前 JVM 保存商品价格，提供容量上限和过期策略。
- Spring JDBC 负责读写数据库，数据库保存权威价格。本实验用内嵌 H2 验证真实提交和回滚。

读路径是 `查 Caffeine → 缓存缺失时查 H2 → 回填缓存`。写路径是 `更新 H2 → 事务提交 → 失效该商品 → 下次读取回填新价`。

## 怎么接

1. 引入 `spring-boot-starter-cache`、`com.github.ben-manes.caffeine:caffeine` 和业务已有的数据访问依赖。本例使用 Spring Boot 3.5.16、Caffeine 3.2.4 和 H2 2.3.232。
2. 在 [缓存配置](src/main/java/io/github/caffeinepricelab/PriceCacheConfiguration.java) 中用 `@EnableCaching` 启用 Spring Cache。建立名为 `productPrices` 的 Caffeine 缓存，示例限制 1000 条、写入后 5 分钟过期。再用 `TransactionAwareCacheManagerProxy` 包装缓存管理器，让普通失效操作在当前 Spring 事务成功提交后执行。
3. [商品服务](src/main/java/io/github/caffeinepricelab/ProductPrices.java) 的 `findPrice` 用 `@Cacheable(sync = true)`，`changePrice` 用 `@Transactional` 和 `@CacheEvict`。两者用同一个缓存名和键 `#p0`，也就是第一个参数商品 ID。从注入的 Spring Bean 调用可触发代理，同类内部直接调用会绕过代理。

`@CacheEvict` 默认在方法正常返回后发起失效。如果写入方法加入了外层事务，返回时外层还可能没提交。示例通过事务感知的缓存管理器把实际失效推迟到提交之后。即时失效操作和 `beforeInvocation=true` 不适用这条时序。

## 怎么验证

从仓库根目录运行以下命令，需要 JDK 17+，无需 Docker 或外部数据库。

```sh
./mvnw -B -f labs/spring-cache-caffeine/pom.xml test
```

| 实验 | 观察结果 |
| --- | --- |
| 首读商品 101，再直接改测试库作为对照 | 首读回填 `19.90`。库里变为 `24.90` 后，同一缓存读取仍返回 `19.90`，证明缓存命中 |
| 在事务里通过服务改价并提交 | 提交前仍保留旧缓存，提交后仅失效商品 101。下次读取取到 `24.90` 并回填，商品 102 仍命中原缓存 |
| 在外层事务里改价后主动回滚 | 数据库和原缓存都保持 `19.90` |
| 在写事务内读取新价后回滚 | 事务内查询看到 `24.90`，未回填缓存，回滚后的查询得到 `19.90` |

同步加载会立即填充缓存。示例在存在实际数据库事务时通过 `@Cacheable` 的 `condition` 绕过缓存，避免未提交的价格被其他请求读到。

## 多实例改价通知与回源合并

A、B 两个应用实例分别保存本机价格缓存。后台提交改价后，每个在线实例都需要收到商品 ID 并清除对应键。Redis Pub/Sub 在这里负责广播，数据库继续保存价格，Caffeine 继续负责各实例的本机缓存。

1. 使用 `spring-boot-starter-data-redis`，给写入端和读价实例启用 `redis-invalidation` profile，并配置同一个 `spring.data.redis.host` 和 `spring.data.redis.port`。各实例的 `lab.price-invalidation.channel` 应相同，不同环境宜用不同频道。
2. 写服务更新数据库后，在事务内发布 Spring 事件。[`RedisPriceInvalidation`](src/main/java/io/github/caffeinepricelab/RedisPriceInvalidation.java) 用 `@TransactionalEventListener(AFTER_COMMIT)` 在提交后将商品 ID 发到 Redis。回滚不进入此监听阶段。
3. 每个实例启动时通过 `RedisMessageListenerContainer` 订阅频道。收到通知后只执行 `cache.evict(productId)`。事务外同键未命中时，`sync = true` 合并本机并发加载，其他实例各自加载。

用一个专供实验的本地 Redis 验证。若已装 Redis，可以在一个终端启动临时实例，结束后按 Ctrl+C 停止它。

```sh
redis-server --bind 127.0.0.1 --port 16379 --save "" --appendonly no
```

在仓库根目录运行以下命令。集成测试创建两个独立 Spring 应用上下文，各有 Caffeine 和 Redis 订阅连接，共用同一个测试 H2 库。Redis 必须在本机，端口可用 `-Dlab.redis.port` 调整。

```sh
./mvnw -B -f labs/spring-cache-caffeine/pom.xml \
  -Dtest=RedisPriceCacheIT -Dlab.redis.port=16379 test
```

| 操作 | 实测断言 |
| --- | --- |
| 两个实例预热，再回滚商品 101 的改价 | 两份缓存仍为 `19.90` |
| 提交商品 101 的改价 | A、B 都清除 101，商品 102 的缓存保留 |
| A、B 各 12 个并发调用查商品 101 | 24 次均返回 `24.90`，Caffeine 统计每个实例成功加载 1 次 |

验证在同一个 JVM 内用两个独立上下文完成，使用真实 Redis 7.4.12。Redis Pub/Sub 不重放断线时的通知，提交与发布之间也有失败窗口，本机 TTL 用作漏通知的兜底。`sync = true` 仅合并单实例同键加载。若集群级回源仍然太多，可进一步验证共享二级缓存和按键合并。批量失效和压测另行验证。

## 官方资料

- [Spring 官方缓存示例](https://github.com/spring-guides/gs-caching) · [Spring Cache 注解和代理](https://docs.spring.io/spring-framework/reference/6.2/integration/cache/annotations.html)
- [事务感知 CacheManager](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/cache/transaction/TransactionAwareCacheManagerProxy.html) · [提交后失效语义](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/cache/transaction/TransactionAwareCacheDecorator.html)
- [Caffeine 官方仓库](https://github.com/ben-manes/caffeine) · [容量和过期策略](https://github.com/ben-manes/caffeine/wiki/Eviction) · [3.2.4 发布记录](https://github.com/ben-manes/caffeine/releases/tag/v3.2.4)
- [Redis 官方仓库](https://github.com/redis/redis) · [Redis Pub/Sub 投递规则](https://redis.io/docs/latest/develop/pubsub/#delivery-semantics) · [Spring Data Redis Pub/Sub 示例](https://docs.spring.io/spring-data/redis/reference/3.5/redis/pubsub.html) · [Spring 事务事件](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/event.html)
