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
3. [商品服务](src/main/java/io/github/caffeinepricelab/ProductPrices.java) 的 `findPrice` 用 `@Cacheable`，`changePrice` 用 `@Transactional` 和 `@CacheEvict`。两者用同一个缓存名和键 `#p0`，也就是第一个参数商品 ID。从注入的 Spring Bean 调用可触发代理，同类内部直接调用会绕过代理。

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

测试覆盖单 JVM 与同一个数据库事务。其他应用实例的本地缓存不会随这次提交一起失效。跨实例同步和并发读写下的回填顺序要单独验证。

## 官方资料

- [Spring 官方缓存示例](https://github.com/spring-guides/gs-caching) · [Spring Cache 注解和代理](https://docs.spring.io/spring-framework/reference/6.2/integration/cache/annotations.html)
- [事务感知 CacheManager](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/cache/transaction/TransactionAwareCacheManagerProxy.html) · [提交后失效语义](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/cache/transaction/TransactionAwareCacheDecorator.html)
- [Caffeine 官方仓库](https://github.com/ben-manes/caffeine) · [容量和过期策略](https://github.com/ben-manes/caffeine/wiki/Eviction) · [3.2.4 发布记录](https://github.com/ben-manes/caffeine/releases/tag/v3.2.4)
