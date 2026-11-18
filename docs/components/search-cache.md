# 检索、分析与缓存

这里记录检索、分析与缓存组件的常用接入和操作。接口、命令和行为以实际部署版本的官方文档为准。

## Elasticsearch

- 常见用途包括全文检索、相关性排序和聚合筛选。创建索引时给全文字段配置 `text` 与分词器，给精确匹配或聚合字段配置 `keyword`。
- 客户端配置集群地址及认证后，使用 `PUT /{index}/_doc/{id}` 按固定 ID 写入或替换文档，用 `_update` 局部更新，使用 `_bulk` 批量操作。搜索用 `_search` 与 `match`、过滤条件或聚合。深分页可使用 PIT 和 `search_after`，带回上一页最后一条的排序值，结束后关闭 PIT。
- 写入与搜索可见之间隔着 refresh。批量响应按条检查状态，区分可重试故障和映射错误。使用并发控制时要区分 Elasticsearch 的 `if_seq_no` / `if_primary_term` 与外部版本。
- 验证刷新前后能否搜索到新文档、批量写入部分失败、并发更新版本冲突，以及 PIT 分页期间并发写入对结果的影响。[Mapping](https://www.elastic.co/docs/manage-data/data-store/mapping) · [近实时搜索](https://www.elastic.co/docs/manage-data/data-store/near-real-time-search) · [Bulk API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-bulk) · [分页](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/paginate-search-results)

## OpenSearch

- 用于全文检索和日志查询。客户应用连接集群后，先建索引和 mapping，写入样本文档，再经查询 API 验证分词、过滤和排序。用索引别名提供稳定查询入口。深分页可以使用 PIT 和 `search_after`。
- refresh 推进索引可见性。用同一个 `_aliases` 请求移除旧指向并添加新指向，可原子切换别名。别名切换只更新索引指向，重建期间的数据变更仍需要追平。别名关联多个索引时，写入需指定写索引。
- 验证 refresh 前后查询，确认切换期间写请求的目标索引。迁移时单独测试实际版本的客户端、映射、插件与查询兼容性。[刷新](https://docs.opensearch.org/latest/api-reference/index-apis/refresh/) · [索引别名](https://docs.opensearch.org/latest/im-plugin/index-alias/) · [分页](https://docs.opensearch.org/latest/search-plugins/searching-data/paginate/)

## Redis

- 选择支持当前部署拓扑的客户端，配置认证、超时和序列化。字符串用 `SET` / `GET` 存缓存、`INCR` 计数，哈希存字段，列表处理有序队列，集合处理成员关系，有序集合按分值排序。用 `SET key value EX seconds` 在写入时指定 TTL，`TTL` 查看剩余时间，`DEL` 删除键。
- Streams 用 `XADD` 追加，`XRANGE` 按 ID 范围读取。通过 `XGROUP CREATE` 建组，`XREADGROUP` 消费，`XACK` 确认，`XPENDING` 查询未确认项，`XAUTOCLAIM` 接手空闲的待处理项。核对裁剪策略和消息保留期，领取与重试可能带来重复处理。[数据结构](https://redis.io/docs/latest/develop/data-types/) · [Streams](https://redis.io/docs/latest/develop/data-types/streams/)
- 键可能按过期或内存策略被移除，`noeviction` 在容量用尽时可能拒绝新增内存的写入。主从复制默认异步，故障转移可能丢失最近确认的写入。缓存删除与数据库提交各自执行，任一步骤失败或并发回填都可能留下旧值。
- 验证过期、内存满、故障转移后读写、缓存删除失败后旧值的存活时间，以及 Streams 消费者退出后待确认消息的接管。[Redis 复制](https://redis.io/docs/latest/operate/oss_and_stack/management/replication/) · [键淘汰](https://redis.io/docs/latest/develop/reference/eviction/)

## Redisson

- Redisson 是面向 Redis 或 Valkey 的 Java 客户端，提供分布式对象和同步原语。为实际部署拓扑创建并复用 `RedissonClient`，使用 `getBucket`、`getMap` 或 `getLock`，退出时调用 `shutdown`。[Redisson 接入](https://redisson.pro/docs/getting-started/) · [分布式对象](https://redisson.pro/docs/data-and-services/objects/) · [集合](https://redisson.pro/docs/data-and-services/collections/)
- `tryLock` 要明确等待时间和租约，确认取得锁后执行操作，并在 `finally` 中释放。释放时若已失去所有权，`unlock` 会报错。未显式指定租约的常规用法依赖 watchdog 续租，显式租约到期会释放。进程长暂停或网络异常可能让旧持有者失锁后继续执行。
- `RFencedLock` 可以取回 fencing token。资源端要随写入原子校验并记录已接受的最大 token，拒绝较小 token。验证持锁进程暂停超过租约、新持有者提交后旧进程恢复、续租失败和主从切换。[锁和 fencing](https://redisson.pro/docs/data-and-services/locks-and-synchronizers/#lock-reliability-and-fencing)

## Lettuce

- Lettuce 是 Java Redis 命令客户端，提供同步、异步和响应式 API。单节点可用 `RedisClient`，集群用 `RedisClusterClient`。通过 `connect()` 取得连接，先验证 `PING`、`SET` 和 `GET`，退出时关闭连接与客户端。
- 同步调用等待返回值，异步调用用 `RedisFuture` 处理完成和错误，响应式调用通过 Reactor 组合并订阅结果。耗时和阻塞操作应离开 Netty 事件循环。普通命令可复用连接，`BLPOP` 或 `MULTI` / `EXEC` 用独占连接，避免干扰同一连接上的其他调用。
- 验证阻塞命令和事务连接隔离、断线重连、命令超时及异步错误传播。客户端超时后，服务端命令仍可能已经执行。[连接](https://redis.github.io/lettuce/user-guide/connecting-redis/) · [异步 API](https://redis.github.io/lettuce/user-guide/async-api/) · [响应式 API](https://redis.github.io/lettuce/user-guide/reactive-api/) · [连接池](https://redis.github.io/lettuce/advanced-usage/connection-pooling/)

## Valkey

- Valkey 提供共享内存键值服务。用支持目标拓扑的客户端或 `valkey-cli` 连到测试实例，从 `PING`、`SET`、`GET` 和 `TTL` 验证基本读写和过期，再测试应用实际使用的脚本、数据类型和命令。[命令参考](https://valkey.io/commands/)
- Redis 迁移到 Valkey 前按版本核对 RESP、命令、脚本、模块及 RDB/AOF 格式。异步复制切换时有数据丢失窗口，验证存量键值、TTL、持久化恢复和故障转移后的读写。[Valkey 迁移指南](https://valkey.io/topics/migration/) · [Valkey 复制](https://valkey.io/topics/replication/)

## Caffeine

- JVM 应用接入 Caffeine 后，用 `Caffeine.newBuilder()` 指定容量或权重上限与过期策略。使用 `Cache.get(key, loader)` 按需加载，或者用 `build(loader)` 创建 `LoadingCache`。显式失效用 `invalidate`。
- 缓存属于单个 JVM，实例重启后会冷启动。各实例分别淘汰和失效，可能读到不同版本。测试并发加载、加载异常和容量淘汰，用可控 `Ticker` 检验过期行为。[Caffeine 加载方式](https://github.com/ben-manes/caffeine/wiki/Population) · [淘汰策略](https://github.com/ben-manes/caffeine/wiki/Eviction)

## ClickHouse

- 用于明细扫描、报表和聚合查询。连接测试实例后创建 `MergeTree` 系列表，按常用过滤条件选择 `ORDER BY` 排序键，用 `INSERT` 批量写入样本，再用 `GROUP BY` 与聚合函数校验结果。排序键允许重复值。
- `ReplacingMergeTree` 在后台合并时按排序键处理重复记录，查询可能看到合并前的多版本数据。需要当前版本时明确查询口径及对应成本，并区分业务去重与插入批次重试去重。
- 追加同排序键的多版本与删除标记，比较普通查询和 `FINAL` 的结果及开销。不同数据批次用样本校验插入重试和回填的结果。[排序键](https://clickhouse.com/docs/concepts/best-practices/choosing-a-primary-key) · [ReplacingMergeTree](https://clickhouse.com/docs/reference/engines/table-engines/mergetree-family/replacingmergetree) · [去重策略](https://clickhouse.com/docs/concepts/features/operations/insert/deduplication)
