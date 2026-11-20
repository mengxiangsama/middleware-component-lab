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

仓库的 Maven 依赖和 Compose 未固定 Redis 服务端或客户端版本。本节以 Redis Open Source 7.4.2 为例，使用 Docker 镜像 `redis:7.4.2` 内的同版本 `redis-cli`。 [Redis 7.4.2 官方发布记录](https://github.com/redis/redis/releases/tag/7.4.2)

| 功能 | 教程状态 | 用法 |
| --- | --- | --- |
| 字符串与计数 | 待补 | 用 `SET` 和 `GET` 读写缓存，用 `INCR` 计数。 |
| 哈希、列表和集合 | 待补 | 哈希存对象字段，列表处理有序队列，集合判断成员关系，有序集合按分值排序。 |
| 键过期 | 已有教程 | [为商品缓存设置 TTL 并在覆盖时保留过期时间](#键过期与缓存更新)。 |
| 内存淘汰 | 待补 | 容量不足时由 `maxmemory-policy` 决定淘汰或拒绝写入。 |
| Streams 消费组确认与接管 | 已有教程 | [跟踪已投递消息并接管退出消费者留下的消息](#streams-消费组确认与接管)。 |
| Streams 历史读取与裁剪 | 已有教程 | [按 ID 分页读取历史并验证裁剪边界](#streams-历史读取与裁剪)。 |
| 发布订阅 | 待补 | 用 `PUBLISH` 和 `SUBSCRIBE` 向在线订阅者广播。 |
| 事务与乐观锁 | 已有教程 | [检测并发库存变更并重试预留事务](#事务与乐观锁)。 |
| 脚本与函数 | 待补 | 用 Lua 脚本或 Redis Functions 封装服务端逻辑。 |
| 持久化、复制与高可用 | 待补 | 用 RDB 或 AOF 持久化，使用 Sentinel 管理非集群部署的故障转移。复制默认异步，故障转移时可能丢失最近确认的写入。 |
| Cluster 分片 | 待补 | 按哈希槽在多个主节点间分布键，由支持 Cluster 的客户端处理重定向。 |

[Redis 数据结构](https://redis.io/docs/latest/develop/data-types/) · [Redis 复制](https://redis.io/docs/latest/operate/oss_and_stack/management/replication/) · [键淘汰](https://redis.io/docs/latest/develop/reference/eviction/)

### 键过期与缓存更新

**适用场景**　商品详情以数据库为准，Redis 缓存允许短期陈旧。首次写缓存时设置 TTL，读到缺失时由应用查数据库并回填。如果更新缓存值时仍须沿用原来的失效时间，用 `SET ... XX KEEPTTL` 更新已有键。若键在更新前已过期，`XX` 会阻止这次写入重新建键。

**接入步骤**　需要 Docker 和 Bash 或 Zsh。使用 `redis:7.4.2` 镜像内的 Redis 服务端和 `redis-cli`，容器不映射宿主机端口。确认两者版本均为 7.4.2，且 `PING` 返回 `"PONG"` 后继续。若刚启动时连接失败，稍后重试 `PING`。

```bash
redis_ttl_lab=$(docker run -d --rm redis:7.4.2)
redis_ttl_cli() {
  docker exec "$redis_ttl_lab" redis-cli --json "$@"
}
docker exec "$redis_ttl_lab" redis-server --version
docker exec "$redis_ttl_lab" redis-cli --version
redis_ttl_cli PING
```

把下面整段复制到同一终端运行。先观察普通 `SET` 覆盖带 TTL 的键时如何清除过期时间。再重新为缓存设定 TTL，更新值时保留原过期时间。最后用 `EXPIRE` 缩短等待时间，验证缓存缺失和条件更新。

```bash
redis_ttl_cli SET cache:product:1001 v1 EX 60
redis_ttl_cli TTL cache:product:1001
redis_ttl_cli SET cache:product:1001 v2
redis_ttl_cli TTL cache:product:1001

redis_ttl_cli SET cache:product:1001 v1 EX 60
redis_ttl_cli SET cache:product:1001 v2 XX KEEPTTL
redis_ttl_cli GET cache:product:1001
redis_ttl_cli TTL cache:product:1001

redis_ttl_cli EXPIRE cache:product:1001 2
sleep 3
redis_ttl_cli GET cache:product:1001
redis_ttl_cli SET cache:product:1001 v3 XX KEEPTTL
redis_ttl_cli TTL cache:product:1001
```

**验证方法**　按下表核对返回值。正数 TTL 会随命令执行时间变化。

| 操作 | 预期结果 |
| --- | --- |
| 写入并设置 TTL，随后普通覆盖 | 两次 `SET` 均返回 `"OK"`，两次 `TTL` 分别返回不超过 60 的正数和 `-1`。 |
| 重新设置 TTL 后更新已有键 | 两次 `SET` 均返回 `"OK"`，`GET` 返回 `"v2"`，`TTL` 仍为不超过 60 的正数。 |
| 缩短 TTL 后等待过期 | `EXPIRE` 返回 `1`。等待后 `GET` 和条件 `SET` 均返回 `null`，`TTL` 返回 `-2`，表示键不存在。 |

验证完毕后用 `docker stop "$redis_ttl_lab"` 停止实验容器。启动时的 `--rm` 会删除容器和匿名卷。

2026-11-20 在 macOS 26.5 arm64、Docker Engine 27.3.1 上用 Bash 执行上述命令。服务端和客户端均报告 7.4.2，`PING` 返回 `"PONG"`。三次 `TTL` 查询依次返回 60、-1 和 60，保留 TTL 的更新后读到 `"v2"`。缩短 TTL 后等待 3 秒，读取和条件更新均返回 `null`，TTL 返回 -2。停止后确认容器已自动删除。

**官方文档**　`SET` 文档列出 `EX`、`XX` 和 `KEEPTTL` 的语法，其中 `KEEPTTL` 自 6.0.0 起可用。7.4.2 版过期测试包含 TTL 和过期行为的断言。 [SET](https://redis.io/docs/latest/commands/set/) · [TTL](https://redis.io/docs/latest/commands/ttl/) · [EXPIRE](https://redis.io/docs/latest/commands/expire/) · [GET](https://redis.io/docs/latest/commands/get/) · [7.4.2 版过期测试](https://raw.githubusercontent.com/redis/redis/7.4.2/tests/unit/expire.tcl)

### Streams 消费组确认与接管

**适用场景**　多个发货 worker 共用一个消费组。某个 worker 领取订单后退出，其他 worker 需要重新处理这条已投递但未确认的事件。前提是事件仍保留在 Stream 中，且业务允许重投。用稳定的业务事件 ID 做幂等，业务写入成功后再确认。生产环境的接管阈值应覆盖正常处理耗时，因为接管时原 worker 仍可能在处理。

**接入步骤**　需要 Docker 和可运行 Bash 或 Zsh 的终端。启动一个不映射宿主机端口的独立容器，使用容器内的客户端。等待 `PING` 返回 `PONG` 后执行后续步骤。

```bash
redis_lab=$(docker run -d --rm redis:7.4.2)
redis_lab_cli() {
  docker exec "$redis_lab" redis-cli --json "$@"
}
docker exec "$redis_lab" redis-server --version
docker exec "$redis_lab" redis-cli --version
redis_lab_cli PING
```

先以 `0-0` 建组，让消费组从流的开头读取。`MKSTREAM` 会在键不存在时创建空 Stream。worker-a 用 `>` 读取尚未投递给该组的消息，省略 ACK 以模拟退出。worker-b 先读取新消息，再用 `XAUTOCLAIM` 接管待确认消息。`1000` 是最短空闲毫秒数，`0-0` 是扫描起点。

```bash
redis_lab_cli XGROUP CREATE orders shipping 0-0 MKSTREAM
order_event_id=$(docker exec "$redis_lab" redis-cli --raw XADD orders '*' orderId 1001)
redis_lab_cli XREADGROUP GROUP shipping worker-a COUNT 1 STREAMS orders '>'
redis_lab_cli XPENDING orders shipping - + 10
redis_lab_cli XREADGROUP GROUP shipping worker-b COUNT 1 STREAMS orders '>'

sleep 2
redis_lab_cli XAUTOCLAIM orders shipping worker-b 1000 0-0 COUNT 10
redis_lab_cli XPENDING orders shipping - + 10
redis_lab_cli XACK orders shipping "$order_event_id"
redis_lab_cli XPENDING orders shipping
redis_lab_cli XRANGE orders "$order_event_id" "$order_event_id"
```

**验证方法**　按顺序核对输出，同一条消息的 ID 应始终与 `order_event_id` 一致。

| 操作 | 预期结果 |
| --- | --- |
| worker-a 读取并退出 | 读到 `orderId=1001`，第一条 `XPENDING` 明细显示归 worker-a，投递次数为 1。 |
| worker-b 读取新消息 | 返回 `null`。消息已投递给该组，仍留在待确认列表。 |
| worker-b 接管 | 返回游标 `0-0`、原消息的 ID 和字段，以及空的已删除 ID 列表。第二条 `XPENDING` 明细显示归 worker-b，投递次数为 2。 |
| 确认后检查 | `XACK` 返回 `1`，`XPENDING` 摘要首项为 `0`，`XRANGE` 仍能读到原消息。 |

批量接管时用返回的游标继续扫描，遇到 `0-0` 时本轮扫描结束。下一轮可再次从 `0-0` 开始，检查后来达到空闲阈值的消息。Redis 7.4 的 `XAUTOCLAIM` 还会返回扫描到的已删除消息 ID，并移除它们在待确认列表中的引用。设置裁剪策略时要留出足够时间完成重投。

2026-11-20 实测环境为 macOS 26.5 arm64 和 Docker Engine 27.3.1。运行上方命令，服务端和客户端均报告 7.4.2，建组返回 `OK`。订单 1001 的消息在待确认列表中从 worker-a 转到 worker-b，投递次数从 1 增至 2。worker-b 尝试读新消息时返回 `null`，接管返回 `0-0` 和空的已删除 ID 列表。确认返回 `1`，待确认数降至 0，原消息仍可读。

**官方文档**　下列命令文档中的参数和返回结构适用于本例的 7.4.2。 [Streams](https://redis.io/docs/latest/develop/data-types/streams/) · [XGROUP CREATE](https://redis.io/docs/latest/commands/xgroup-create/) · [XADD](https://redis.io/docs/latest/commands/xadd/) · [XREADGROUP](https://redis.io/docs/latest/commands/xreadgroup/) · [XPENDING](https://redis.io/docs/latest/commands/xpending/) · [XAUTOCLAIM](https://redis.io/docs/latest/commands/xautoclaim/) · [XACK](https://redis.io/docs/latest/commands/xack/) · [XRANGE](https://redis.io/docs/latest/commands/xrange/)

完成后停止容器，`--rm` 会删除该实验容器和匿名卷。

```bash
docker stop "$redis_lab"
```

### Streams 历史读取与裁剪

**适用场景**　订单事件排查工具需要按 ID 分页查看近期记录，服务也需要定期清理超出保留范围的历史。适用于允许旧事件退出回放窗口的 Stream。若同一 Stream 还有消费组，应在裁剪前确认所有相关消费组都已处理待删除的事件。Redis 7.4 的裁剪可以删除尚未确认的消息正文，影响后续重投。

**接入步骤**　需要 Docker 和 Bash 或 Zsh。用独立容器运行 Redis Open Source 7.4.2 和容器内的同版本 `redis-cli`，无需映射端口。确认 `PING` 返回 `PONG` 后再写入数据。

```bash
redis_history_lab=$(docker run -d --rm redis:7.4.2)
redis_history_cli() {
  docker exec "$redis_history_lab" redis-cli --json "$@"
}
docker exec "$redis_history_lab" redis-server --version
docker exec "$redis_history_lab" redis-cli --version
redis_history_cli PING
```

写入四条带固定 ID 的测试事件，让前三条记录共享同一毫秒值。第一页最多读两条，取其最后一条的完整 ID `1000-1`，加上 `(` 作为第二页的排他起点。`-` 和 `+` 分别代表最小和最大 ID。

```bash
redis_history_cli XADD order-history 1000-0 orderId 1001
redis_history_cli XADD order-history 1000-1 orderId 1002
redis_history_cli XADD order-history 1000-2 orderId 1003
redis_history_cli XADD order-history 1001-0 orderId 1004
redis_history_cli XRANGE order-history - + COUNT 2
redis_history_cli XRANGE order-history '(1000-1' + COUNT 2
```

用 `MINID = 1000-2` 精确删除 ID 小于阈值的记录，阈值本身仍保留。若保留策略按条数制定，可用 `MAXLEN =` 保留最近的指定条数。本例先验证 ID 边界，再把剩余记录裁剪到一条。

```bash
redis_history_cli XTRIM order-history MINID = 1000-2
redis_history_cli XRANGE order-history - +
redis_history_cli XTRIM order-history MAXLEN = 1
redis_history_cli XRANGE order-history - +
```

**验证方法**　逐步核对 ID 和删除数量。

| 操作 | 预期结果 |
| --- | --- |
| 分页 | 第一页为 `1000-0`、`1000-1`，第二页为 `1000-2`、`1001-0`。即便下一条与游标处于同一毫秒，也能继续读取。 |
| 按 ID 裁剪 | `XTRIM` 返回 `2`，剩余 `1000-2`、`1001-0`。 |
| 按长度裁剪 | `XTRIM` 返回 `1`，仅剩 `1001-0`。 |

分页期间若有并发追加或裁剪，每页读到的是执行当时仍保留的记录。需要完整回放时，应保证回放结束前所需记录仍在保留范围内。允许暂时多保留一些记录时，可以考虑用 `~` 做近似裁剪。

2026-11-20 在 macOS 26.5 arm64 和 Docker Engine 27.3.1 上运行本节命令。服务端与客户端均报告 7.4.2，`PING` 返回 `PONG`。两页各返回两条记录，第二页从同一毫秒下的 `1000-2` 开始。两次裁剪分别删除 2 条和 1 条，按 ID 裁剪后阈值仍可读，按长度裁剪后仅剩 `1001-0`。停止后容器已自动删除。

**官方文档**　命令历史和 7.4.2 源码可用于核对本例中的参数。 [XADD](https://redis.io/docs/latest/commands/xadd/) · [XRANGE](https://redis.io/docs/latest/commands/xrange/) · [XTRIM](https://redis.io/docs/latest/commands/xtrim/) · [7.4.2 XTRIM 命令定义](https://raw.githubusercontent.com/redis/redis/7.4.2/src/commands/xtrim.json)

完成后停止容器，`--rm` 会删除该实验容器和匿名卷。

```bash
docker stop "$redis_history_lab"
```

### 事务与乐观锁

**适用场景**　预留商品时，应用先检查可用库存，再扣减可用量并增加预留量。两个键都保存在同一个 Redis 实例中，库存冲突较少，应用可以在冲突后重新读取再作决定。示例使用初始化为整数且不会过期的两个测试键。

**接入步骤**　需要 Docker 和两个运行 Bash 或 Zsh 的终端。使用 Redis Open Source 7.4.2 和镜像内同版本的 `redis-cli`，无需映射端口。在终端 A 启动容器，确认 `PING` 返回 `PONG`，将容器 ID 记下来供终端 B 使用。

```bash
redis_tx_lab=$(docker run -d --rm redis:7.4.2)
echo "$redis_tx_lab"
docker exec "$redis_tx_lab" redis-server --version
docker exec "$redis_tx_lab" redis-cli --version
docker exec "$redis_tx_lab" redis-cli PING
```

初始化库存和预留量，再打开交互式客户端。

```bash
docker exec "$redis_tx_lab" redis-cli MSET stock:sku-42 5 reserved:sku-42 0
docker exec -it "$redis_tx_lab" redis-cli --json
```

在 A 的 `redis-cli` 提示符下逐条输入以下命令。先用 `WATCH` 监视可用库存，再用 `GET` 读取。确认库存至少为 2 后再开启事务。两条写命令返回 `"QUEUED"` 后，保留当前连接。

```text
WATCH stock:sku-42
GET stock:sku-42
MULTI
DECRBY stock:sku-42 2
INCRBY reserved:sku-42 2
```

在终端 B 将变量值换成 A 打印的容器 ID，通过另一条连接模拟补货。

```bash
redis_tx_lab=replace-with-container-id
docker exec "$redis_tx_lab" redis-cli --json INCR stock:sku-42
```

回到 A 的同一个 `redis-cli` 会话，先提交并查询两个值。监视状态会在 `EXEC` 后清除，因此重试时要重新执行 `WATCH`，并用新库存检查是否还够预留 2 件。

```text
EXEC
MGET stock:sku-42 reserved:sku-42
WATCH stock:sku-42
GET stock:sku-42
MULTI
DECRBY stock:sku-42 2
INCRBY reserved:sku-42 2
EXEC
MGET stock:sku-42 reserved:sku-42
```

**验证方法**　对照两次提交及各自的查询结果。

| 操作 | 预期结果 |
| --- | --- |
| 首次读取与补货 | A 的 `GET` 返回 `"5"`，B 的 `INCR` 返回 `6`。 |
| 冲突时提交 | `EXEC` 返回 `null`，`MGET` 返回 `["6","0"]`，两条预留命令都未执行。 |
| 读取新库存并重试 | `GET` 返回 `"6"`，`EXEC` 返回 `[4,2]`，`MGET` 返回 `["4","2"]`。 |

应用需要在同一条连接完成从 `WATCH` 到 `EXEC` 的操作。遇到冲突时限制重试次数，每次重新检查业务条件。若读到库存不足且尚未执行 `MULTI`，可用 `UNWATCH` 清除监视。提交成功时也要检查返回数组中的每条回复，运行时出错的命令不会让其他已执行的命令回滚。

在 A 的客户端输入 `QUIT` 退出，再停止容器。启动时指定的 `--rm` 会删除容器和匿名卷。

```bash
docker stop "$redis_tx_lab"
```

2026-11-20 在 macOS 26.5 arm64、Docker Engine 27.3.1 上运行上述命令。服务端和客户端均报告 7.4.2，`PING` 返回 `PONG`。补货后首次提交返回 `null`，库存和预留量为 6 和 0。重试返回 `[4,2]`，查询确认库存为 4、预留量为 2。停止后确认实验容器已自动删除。

**官方文档**　命令文档和 7.4.2 版事务测试可用于核对命令语法及提交结果。 [Redis 事务](https://redis.io/docs/latest/develop/using-commands/transactions/) · [WATCH](https://redis.io/docs/latest/commands/watch/) · [MULTI](https://redis.io/docs/latest/commands/multi/) · [EXEC](https://redis.io/docs/latest/commands/exec/) · [7.4.2 版事务测试](https://raw.githubusercontent.com/redis/redis/7.4.2/tests/unit/multi.tcl)

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
