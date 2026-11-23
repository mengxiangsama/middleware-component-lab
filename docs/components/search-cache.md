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

仓库的 Maven 依赖和 Compose 未固定 Redis 服务端或客户端版本。本节以 Redis Open Source 7.4.2 和同版本的 `redis-cli` 为例。大部分示例使用 Docker 镜像 `redis:7.4.2`，RDB、AOF、主从复制和键空间通知实验使用源码构建。 [Redis 7.4.2 官方发布记录](https://github.com/redis/redis/releases/tag/7.4.2)

| 功能 | 教程状态 | 用法 |
| --- | --- | --- |
| 字符串与计数 | 已有教程 | [用 `SET`、`GET` 和 `INCR` 累计页面访问次数](#字符串页面访问计数)。 |
| 哈希 | 已有教程 | [按 SKU 保存和更新购物车商品数量](#哈希购物车数量更新)。 |
| 列表 | 已有教程 | [按入队顺序领取任务、确认成功并手动重入队](#列表任务领取与确认)。 |
| 集合 | 已有教程 | [按用户 ID 去重、判断成员关系并查询名单交集](#集合成员与交集)。 |
| 有序集合 | 已有教程 | [更新积分并查询排行榜和个人名次](#有序集合积分排行)。 |
| Bitmap | 已有教程 | [记录每日签到并统计连续签到人数](#bitmap-每日签到与交集)。 |
| 地理空间索引 | 已有教程 | [按距离查找附近门店并更新门店坐标](#地理空间索引查询附近门店)。 |
| HyperLogLog | 已有教程 | [估算单日及跨日独立访客](#hyperloglog-独立访客估算)。 |
| 键过期 | 已有教程 | [为商品缓存设置 TTL 并在覆盖时保留过期时间](#键过期与缓存更新)。 |
| 内存淘汰 | 已有教程 | [对比写入被拒与带 TTL 的缓存键被淘汰](#缓存内存上限与键淘汰)。 |
| Streams 消费组确认与接管 | 已有教程 | [跟踪已投递消息并接管退出消费者留下的消息](#streams-消费组确认与接管)。 |
| Streams 历史读取与裁剪 | 已有教程 | [按 ID 分页读取历史并验证裁剪边界](#streams-历史读取与裁剪)。 |
| 发布订阅 | 已有教程 | [广播缓存失效通知并验证断线期间的消息不会重放](#发布订阅与缓存失效通知)。 |
| 键空间通知 | 已有教程 | [订阅商品缓存键过期通知，核对两种频道的消息内容](#键空间通知与缓存过期观察)。 |
| 事务与乐观锁 | 已有教程 | [检测并发库存变更并重试预留事务](#事务与乐观锁)。 |
| Lua 脚本 | 已有教程 | [原子检查并更新固定窗口限流计数](#lua-固定窗口限流)。 |
| Redis Functions | 已有教程 | [注册函数库并原子扣减单件库存](#redis-functions-单件库存扣减)。 |
| RDB 快照 | 已有教程 | [保存库存快照并验证重启后的恢复边界](#rdb-快照与重启恢复)。 |
| AOF 持久化 | 已有教程 | [记录库存写入并在启动时重放增量日志](#aof-写入日志与重启恢复)。 |
| 主从复制 | 已有教程 | [将库存读模型复制到只读副本](#主从复制与副本只读验证)。 |
| Sentinel 高可用 | 待补 | 监控非集群部署的主从节点，并在主节点故障时协调切换。 |
| Cluster 分片 | 待补 | 按哈希槽在多个主节点间分布键，由支持 Cluster 的客户端处理重定向。 |

[Redis 数据结构](https://redis.io/docs/latest/develop/data-types/) · [Redis 复制](https://redis.io/docs/latest/operate/oss_and_stack/management/replication/) · [键淘汰](https://redis.io/docs/latest/develop/reference/eviction/)

### 字符串页面访问计数

**适用场景**　多个应用实例为同一商品页面累计访问次数。每次计入一次访问时执行一条 `INCR`，Redis 原子完成读取、加一和写回，避免客户端分别执行 `GET` 和 `SET` 时覆盖其他连接的增量。同一请求重复执行会再次加一，需要按请求去重时应另行记录请求 ID。计数值须为十进制 64 位有符号整数。

**接入步骤**　需要 Docker 和 Bash 或 Zsh。使用 `redis:7.4.2` 中的服务端与同版本的 `redis-cli`，容器不映射宿主机端口。若服务仍在启动，重试 `PING` 直至返回 `PONG`。

```bash
redis_counter_lab=$(docker run -d --rm --network none redis:7.4.2)
docker exec "$redis_counter_lab" redis-server --version
docker exec "$redis_counter_lab" redis-cli --version
docker exec "$redis_counter_lab" redis-cli PING
```

**验证方法**　每条 `redis-cli` 命令都打开一个独立连接。新页面的计数键不存在，首次 `INCR` 按初值 0 计算。已有历史计数的页面可在启用计数前用 `SET` 写入基数。普通 `SET` 会覆盖已有值，应避免在计数期间用它初始化同一个键。

```bash
docker exec "$redis_counter_lab" redis-cli INCR lab:views:sku42
docker exec "$redis_counter_lab" redis-cli INCR lab:views:sku42
docker exec "$redis_counter_lab" redis-cli GET lab:views:sku42
docker exec "$redis_counter_lab" redis-cli SET lab:views:sku43 10
docker exec "$redis_counter_lab" redis-cli INCR lab:views:sku43
docker exec "$redis_counter_lab" redis-cli GET lab:views:sku43
```

前两次自增依次返回 `1` 和 `2`，读取结果为 `2`。初始化历史计数返回 `OK`，再自增和读取均得到 `11`。

```bash
docker exec "$redis_counter_lab" redis-cli SET lab:views:invalid unknown
docker exec "$redis_counter_lab" redis-cli INCR lab:views:invalid
docker exec "$redis_counter_lab" redis-cli GET lab:views:invalid
docker exec "$redis_counter_lab" redis-cli SET lab:views:max 9223372036854775807
docker exec "$redis_counter_lab" redis-cli INCR lab:views:max
docker exec "$redis_counter_lab" redis-cli GET lab:views:max
docker stop "$redis_counter_lab"
```

两次 `SET` 均返回 `OK`。非整数字符串和整数溢出分别触发 `ERR value is not an integer or out of range` 和 `ERR increment or decrement would overflow`，随后的读取仍分别得到 `unknown` 和 `9223372036854775807`。停止实验容器后，Docker 通过 `--rm` 清理容器和匿名卷。

2026-11-23 在 macOS 26.5 arm64、Docker Engine 27.3.1 和 Bash 3.2.57 上运行了以上三段命令。服务端和客户端均报告 7.4.2，`PING` 返回 `PONG`。新键两次自增返回 `1` 和 `2`，读取为 `2`。历史计数从 `10` 增至 `11`，读取为 `11`。两种边界输入均返回上述错误，随后读取的值与写入值一致。用 `docker container inspect` 和 `docker volume inspect` 确认实验容器与匿名卷已删除。

**官方文档**　以下三个命令自 Redis 1.0.0 起可用，示例仅使用 Redis 7.4.2 支持的基本语法。 [SET](https://redis.io/docs/latest/commands/set/) · [GET](https://redis.io/docs/latest/commands/get/) · [INCR](https://redis.io/docs/latest/commands/incr/)

### 列表任务领取与确认

**适用场景**　已有 Redis 的小型后台任务队列，例如生成缩略图。生产者按入队顺序追加任务，消费者领取时把任务移入处理中列表，成功后再确认。每条任务使用唯一 ID，同一任务的业务处理需要幂等。多个消费者可以按入队顺序领取，但完成顺序取决于各自的处理时间。

**接入步骤**　需要 Docker 和 Bash 或 Zsh。使用 `redis:7.4.2` 镜像和镜像内同版本的 `redis-cli`，无需映射宿主机端口。若服务仍在启动，重试 `PING` 直至返回 `"PONG"`。

```bash
redis_list_lab=$(docker run -d --rm redis:7.4.2)
list_cli() {
  docker exec "$redis_list_lab" redis-cli --json "$@"
}
docker exec "$redis_list_lab" redis-server --version
docker exec "$redis_list_lab" redis-cli --version
list_cli PING
```

`RPUSH` 从待处理列表尾部入队，`BLMOVE LEFT RIGHT` 原子地从待处理列表头部取走一项并追加到处理中列表尾部。`BLMOVE` 的最后一个参数是等待秒数。成功处理后，用 `LREM` 的正数参数 `1` 从表头开始移除首个匹配的任务 ID。两个键使用共同的 `{thumbnails}` 哈希标签，以满足 Redis Cluster 对多键操作的同槽要求。

```bash
ready='lab:{thumbnails}:ready'
processing='lab:{thumbnails}:processing'
list_cli RPUSH "$ready" job-101 job-102
list_cli BLMOVE "$ready" "$processing" LEFT RIGHT 1
list_cli LRANGE "$processing" 0 -1
# 模拟 job-101 处理成功
list_cli LREM "$processing" 1 job-101
```

**验证方法**　在同一终端领取第二项，模拟消费者领取后退出，跳过确认。确认原消费者已经停止后，由操作者用 `LMOVE` 将本例唯一一项未确认任务移回待处理列表。再次领取并确认，最后检查空队列的等待超时和处理中列表。

```bash
list_cli BLMOVE "$ready" "$processing" LEFT RIGHT 1
list_cli LRANGE "$processing" 0 -1
list_cli LMOVE "$processing" "$ready" LEFT RIGHT
list_cli LRANGE "$ready" 0 -1
list_cli BLMOVE "$ready" "$processing" LEFT RIGHT 1
list_cli LREM "$processing" 1 job-102
list_cli BLMOVE "$ready" "$processing" LEFT RIGHT 1
list_cli LRANGE "$processing" 0 -1
```

| 操作 | 预期结果 |
| --- | --- |
| 入队并确认首项 | `RPUSH` 返回 `2`，首次领取返回 `"job-101"`，处理中列表返回 `["job-101"]`，确认返回 `1`。 |
| 中断后重入队 | 第二次领取返回 `"job-102"`，未确认时处理中列表仍有 `["job-102"]`。`LMOVE` 返回 `"job-102"`，待处理列表显示 `["job-102"]`。 |
| 重领、确认并检查队列 | 重领返回 `"job-102"`，确认返回 `1`。再领取等待约一秒后返回 `null`，处理中列表返回 `[]`。 |

列表不会自动记录领取时间或重投。多消费者自动恢复需要跟踪任务归属和超时，协调确认与回收，避免把仍在执行的任务移回队列。若需要按消费者追踪未确认消息并按空闲时间接管，可参考 [Streams 消费组确认与接管](#streams-消费组确认与接管)。

完成后停止容器，`--rm` 会自动清理容器和匿名卷。

```bash
docker stop "$redis_list_lab"
```

2026-11-23 在 macOS 26.5 arm64、Docker Engine 27.3.1 上运行上述命令。服务端与客户端均报告 7.4.2，`PING` 返回 `"PONG"`。依次领到 `job-101` 和 `job-102`，跳过第二项的确认后仍可在处理中列表查到它。手动移回、重新领取并确认后，空队列的 `BLMOVE` 返回 `null`，处理中列表返回 `[]`。停止后确认实验容器已自动删除。

**官方文档**　`LMOVE` 和 `BLMOVE` 自 Redis 6.2 提供。 [RPUSH](https://redis.io/docs/latest/commands/rpush/) · [LMOVE](https://redis.io/docs/latest/commands/lmove/) · [BLMOVE](https://redis.io/docs/latest/commands/blmove/) · [LREM](https://redis.io/docs/latest/commands/lrem/) · [LRANGE](https://redis.io/docs/latest/commands/lrange/) · [Redis 7.4.2 列表命令测试](https://raw.githubusercontent.com/redis/redis/7.4.2/tests/unit/type/list.tcl)

### 地理空间索引查询附近门店

**适用场景**　按用户坐标筛选一定半径内的自提门店，再按距离排序。门店有稳定 ID，坐标使用同一坐标系，业务接受基于球面距离的筛选。步行距离或预计到店时间还需要路线计算。

**接入步骤**　需要 Docker 和 Bash 或 Zsh。使用 `redis:7.4.2` 镜像内的 Redis 服务端和同版本 `redis-cli`，无需映射宿主机端口。等 `PING` 返回 `"PONG"` 后继续，若服务仍在启动可稍后重试。

```bash
redis_geo_lab=$(docker run -d --rm redis:7.4.2)
redis_geo_cli() {
  docker exec "$redis_geo_lab" redis-cli --json "$@"
}
docker exec "$redis_geo_lab" redis-server --version
docker exec "$redis_geo_lab" redis-cli --version
redis_geo_cli PING
```

以经度、纬度、门店 ID 的顺序写入三条测试数据。用用户的经纬度作为圆心，查询 2 公里内的门店。`ASC` 按距离从近到远排序，`WITHDIST` 返回以公里为单位的距离。只取最近一项时用 `ASC COUNT 1`。如果加上 `ANY`，服务端可能提前结束搜索，结果可能漏掉更近的门店。

```bash
redis_geo_cli GEOADD stores:geo 121.480 31.230 store-a 121.490 31.230 store-b 121.550 31.230 store-c
redis_geo_cli GEOSEARCH stores:geo FROMLONLAT 121.475 31.230 BYRADIUS 2 km ASC WITHDIST
redis_geo_cli GEOSEARCH stores:geo FROMLONLAT 121.475 31.230 BYRADIUS 2 km ASC COUNT 1
```

模拟 `store-a` 搬迁。`XX` 只更新已有成员，`CH` 让返回值计入坐标发生变化的成员。用相同的圆心和半径再查一次。

```bash
redis_geo_cli GEOADD stores:geo XX CH 121.530 31.230 store-a
redis_geo_cli GEOSEARCH stores:geo FROMLONLAT 121.475 31.230 BYRADIUS 2 km ASC WITHDIST
```

**验证方法**　核对命令返回值、门店顺序和距离。

| 操作 | 预期结果 |
| --- | --- |
| 写入并查询半径内门店 | `GEOADD` 返回 `3`。查询依次返回 `store-a`（约 0.48 公里）、`store-b`（约 1.43 公里）。 |
| 只取最近一项 | 返回 `["store-a"]`。 |
| 搬迁后按原条件查询 | 更新返回 `1`，随后查询仅返回 `store-b`。 |

完成后停止容器，`--rm` 会自动删除实验容器和匿名卷。

```bash
docker stop "$redis_geo_lab"
```

2026-11-23 在 macOS 26.5 arm64、Docker Engine 27.3.1 上运行上述命令。服务端和客户端均报告 7.4.2，`PING` 返回 `"PONG"`。初次搜索返回 `[["store-a","0.4756"],["store-b","1.4267"]]`，限量查询返回 `["store-a"]`。更新返回 `1`，再次搜索仅返回 `[["store-b","1.4267"]]`。停止后确认容器已自动删除。

**官方文档**　`GEOADD` 自 Redis 3.2 提供，`NX`、`XX`、`CH` 自 6.2 提供，`GEOSEARCH` 自 6.2 提供。 [GEOADD](https://redis.io/docs/latest/commands/geoadd/) · [GEOSEARCH](https://redis.io/docs/latest/commands/geosearch/) · [Redis 7.4.2 地理空间命令测试](https://raw.githubusercontent.com/redis/redis/7.4.2/tests/unit/geo.tcl)

### Bitmap 每日签到与交集

**适用场景**　每天用一张位图记录用户是否签到，查询单个用户的签到状态、当日人数和连续两天都签到的人数。Redis 在字符串中保存位图。每个用户需要跨日使用同一个稳定、稠密的非负整数偏移。写入很大的偏移会分配中间字节，稀疏的大 ID 应先映射到稠密编号。整张位图的计数和交集需要扫描字节，数据量大时应评估命令耗时。

**接入步骤**　需要 Docker 和 Bash 或 Zsh。使用 `redis:7.4.2` 内的 Redis 服务端和同版本 `redis-cli`，无需映射宿主机端口。若刚启动时连接失败，重试 `PING` 直至返回 `PONG`，再写入两天的签到数据。每次 `SETBIT` 返回写入前的位值，下面四次都应返回 `0`。

```bash
redis_bitmap_lab=$(docker run -d --rm redis:7.4.2)
docker exec "$redis_bitmap_lab" redis-server --version
docker exec "$redis_bitmap_lab" redis-cli --version

bitmap_cli() {
  docker exec "$redis_bitmap_lab" redis-cli --raw "$@"
}

bitmap_cli PING
day1='lab:{checkin}:2026-11-21'
day2='lab:{checkin}:2026-11-22'
both='lab:{checkin}:both'
bitmap_cli SETBIT "$day1" 2 1
bitmap_cli SETBIT "$day1" 7 1
bitmap_cli SETBIT "$day2" 2 1
bitmap_cli SETBIT "$day2" 9 1
```

**验证方法**　在同一终端继续执行。Redis Cluster 中，`BITOP` 的源键和目标键必须位于同一哈希槽，本例用共同的 `{checkin}` 哈希标签。交集写入独立的目标键，保留两个日期的原始记录。

```bash
bitmap_cli SETBIT "$day1" 2 1
bitmap_cli GETBIT "$day1" 2
bitmap_cli GETBIT "$day1" 9
bitmap_cli BITCOUNT "$day1"
bitmap_cli BITCOUNT "$day2"
bitmap_cli BITOP AND "$both" "$day1" "$day2"
bitmap_cli BITCOUNT "$both"
bitmap_cli GETBIT "$both" 2
bitmap_cli GETBIT "$both" 9
docker stop "$redis_bitmap_lab"
```

重复签到返回旧值 `1`。第一天的两个位查询依次返回 `1` 和 `0`，两天各有 2 人签到。第二天写入了偏移 9，长度为 2 字节。求交集时第一天的位图补零到相同长度，`BITOP AND` 返回结果的字节数 `2`。随后 `BITCOUNT` 返回人数 `1`，目标位图在偏移 2 和 9 的查询分别返回 `1` 和 `0`。停止容器后，`--rm` 会清理容器和匿名卷。

2026-11-22 在 macOS 26.5 arm64、Docker Engine 27.3.1 和 Bash 3.2.57 上执行了上述命令并逐项核对返回值。服务端和客户端均报告 7.4.2，`PING` 返回 `PONG`。首次写入均返回 0，重复签到返回 1，两日人数均为 2。交集长 2 字节，计数为 1，仅偏移 2 对应的用户两天都签到了。执行 `docker container inspect "$redis_bitmap_lab"` 确认停止后容器已删除。

**官方文档**　[Redis Bitmap](https://redis.io/docs/latest/develop/data-types/strings/bitmaps/) · [SETBIT](https://redis.io/docs/latest/commands/setbit/) · [GETBIT](https://redis.io/docs/latest/commands/getbit/) · [BITCOUNT](https://redis.io/docs/latest/commands/bitcount/) · [BITOP](https://redis.io/docs/latest/commands/bitop/) · [Redis 7.4.2 位操作测试](https://raw.githubusercontent.com/redis/redis/7.4.2/tests/unit/bitops.tcl) · [Redis Cluster 哈希标签](https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/#hash-tags)

### 集合成员与交集

**适用场景**　活动系统用用户 ID 记录报名名单和资格名单。Redis Set 保存无序、不重复的成员，适合需要判定单个用户是否报名，或找出同时报名且具备资格的用户。单次报名查询用 `SISMEMBER`，名单规模可控时用 `SINTER` 批量取交集。交集会返回全部匹配的成员，大名单需要评估计算和响应开销。在 Redis Cluster 上执行多键交集时，各键必须位于同一哈希槽，本例用相同的 `{spring-sale}` 哈希标签。

**接入步骤**　需要 Docker 和 Bash 或 Zsh。启动独立的 Redis Open Source 7.4.2 容器，使用容器内同版本的 `redis-cli`，无需映射端口。若刚启动时连接失败，稍后重试 `PING`，直到返回 `PONG`。

```bash
redis_set_lab=$(docker run -d --rm redis:7.4.2)
docker exec "$redis_set_lab" redis-server --version
docker exec "$redis_set_lab" redis-cli --version

set_cli() {
  docker exec "$redis_set_lab" redis-cli --raw "$@"
}

set_cli PING
set_cli SADD 'lab:{spring-sale}:registered' u101 u102 u104
set_cli SADD 'lab:{spring-sale}:eligible' u102 u103 u104
```

两次 `SADD` 各返回 `3`，表示各新增三名用户。

**验证方法**　在同一终端依次运行下面的命令。对交集结果排序仅用于核对输出，因为 Redis Set 无固定返回顺序。最后停止容器，由 `--rm` 清理容器及其匿名卷。

```bash
set_cli SADD 'lab:{spring-sale}:registered' u102
set_cli SISMEMBER 'lab:{spring-sale}:registered' u102
set_cli SISMEMBER 'lab:{spring-sale}:registered' u103
set_cli SINTER 'lab:{spring-sale}:registered' 'lab:{spring-sale}:eligible' | LC_ALL=C sort
docker stop "$redis_set_lab"
```

重复报名返回 `0`，因为没有新增成员。两次成员查询依次返回 `1`、`0`，分别表示已报名和未报名。交集结果应逐行输出 `u102`、`u104`。

2026-11-21 在 macOS 26.5 arm64、Docker Engine 27.3.1 和 Bash 3.2.57 上运行了上述命令。服务端与客户端均报告 7.4.2，启动后 `PING` 返回 `PONG`。两份名单各新增 3 人，重复添加返回 `0`，成员查询返回 `1`、`0`，排序后的交集为 `u102`、`u104`。停止后通过 `docker container inspect "$redis_set_lab"` 确认容器已删除。

**官方文档**　[Redis Sets](https://redis.io/docs/latest/develop/data-types/sets/) · [SADD](https://redis.io/docs/latest/commands/sadd/) · [SISMEMBER](https://redis.io/docs/latest/commands/sismember/) · [SINTER](https://redis.io/docs/latest/commands/sinter/) · [Redis 7.4.2 集合测试](https://raw.githubusercontent.com/redis/redis/7.4.2/tests/unit/type/set.tcl) · [Redis Cluster 哈希标签](https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/#hash-tags)

### Lua 固定窗口限流

**适用场景**　多个应用实例共享某个用户在同一 API 上的请求额度。窗口从首个请求开始，本例允许 10 秒内通过 2 次。脚本在 Redis 内一次完成读取、限额判断和更新，其他请求无法插入这些操作之间。窗口交界处可能短时间内通过两批请求，适合允许这种突发流量的接口。每个用户和接口组合使用独立的计数键，所有实例使用相同的限额和窗口，计数键由脚本独占。

**接入步骤**　需要 Docker 和 Bash 或 Zsh。使用 `redis:7.4.2` 启动独立容器，服务端与 `redis-cli` 均为 7.4.2，无需映射宿主机端口。若刚启动时连接失败，稍后重试 `PING`，直到返回 `PONG`。

```bash
redis_rate_lab=$(docker run -d --rm redis:7.4.2)
docker exec "$redis_rate_lab" redis-server --version
docker exec "$redis_rate_lab" redis-cli --version
docker exec "$redis_rate_lab" redis-cli PING
```

`EVAL` 后的 `1` 表示传入一个键，脚本通过 `KEYS[1]` 访问该键。`ARGV[1]` 和 `ARGV[2]` 分别接收限额和窗口秒数，调用方传入已校验的正整数。首次调用用 `SET ... EX` 一次写入计数及其过期时间，窗口内的 `INCR` 保留原过期时间。返回 `1` 表示放行，`0` 表示拒绝。

```bash
rate_script=$(cat <<'LUA'
local key = KEYS[1]
local count = redis.call('GET', key)

if not count then
    redis.call('SET', key, '1', 'EX', ARGV[2])
    return 1
end
if tonumber(count) >= tonumber(ARGV[1]) then
    return 0
end
redis.call('INCR', key)
return 1
LUA
)
rate_key=lab:rate:checkout:user42

rate_request() {
  docker exec "$redis_rate_lab" redis-cli EVAL "$rate_script" 1 "$rate_key" 2 10
}
```

**验证方法**　在同一终端连续运行下面整段命令，避免在前三次调用之间因手工停顿跨过窗口。`EXPIRETIME` 返回绝对过期时间，可检查第二次放行和第三次拒绝是否延长了窗口。

```bash
rate_request
docker exec "$redis_rate_lab" redis-cli EXPIRETIME "$rate_key"
sleep 2
rate_request
rate_request
docker exec "$redis_rate_lab" redis-cli GET "$rate_key"
docker exec "$redis_rate_lab" redis-cli EXPIRETIME "$rate_key"
sleep 10
docker exec "$redis_rate_lab" redis-cli EXPIRETIME "$rate_key"
rate_request
docker exec "$redis_rate_lab" redis-cli GET "$rate_key"
docker stop "$redis_rate_lab"
```

前三次调用依次返回 `1`、`1`、`0`。两次读取的过期时间是同一个正整数，拒绝后的计数为 `2`。等待后，`EXPIRETIME` 返回 `-2`，表示键已过期。再调用一次返回 `1`，新窗口的计数为 `1`。`docker stop` 触发 `--rm` 清理容器和匿名卷。

2026-11-20 在 macOS 26.5 arm64、Docker Engine 27.3.1 上用 Bash 运行了上述三段命令。服务端和客户端均报告 7.4.2，`PING` 返回 `PONG`。前三次调用返回 `1`、`1`、`0`，两次过期时间均为 `1795171902`，计数为 `2`。等待后读到 `-2`，再调用返回 `1`，计数从 `1` 开始。停止后用 `docker container inspect "$redis_rate_lab"` 确认容器已删除。

**官方文档**　Lua 文档说明了脚本的原子执行，Redis 7.4.2 的仓库测试覆盖了 `KEYS` 和 `ARGV` 的传参及脚本内调用命令。 [Lua 脚本](https://redis.io/docs/latest/develop/programmability/eval-intro/) · [EVAL](https://redis.io/docs/latest/commands/eval/) · [INCR 限流示例](https://redis.io/docs/latest/commands/incr/) · [SET](https://redis.io/docs/latest/commands/set/) · [EXPIRETIME](https://redis.io/docs/latest/commands/expiretime/) · [7.4.2 版脚本测试](https://raw.githubusercontent.com/redis/redis/7.4.2/tests/unit/scripting.tcl)

### Redis Functions 单件库存扣减

**适用场景**　多个应用实例需要共用一条扣减规则，每次为同一 SKU 扣减一件库存。把 Lua 函数随服务端部署，调用方只传函数名和库存键。Redis 原子执行库存检查与扣减，其他客户端无法在这两步之间修改库存。库存键应由业务初始化为非负整数字符串，并由同一库存模型管理。函数执行期间会阻塞其他命令，适合这种执行时间短的逻辑。请求超时后直接重试可能重复扣减，业务需要按订单或请求 ID 做幂等控制。

**接入步骤**　需要 Docker 和 Bash 或 Zsh。使用 `redis:7.4.2` 的服务端和同版本 `redis-cli`。以 `--network none` 启动单节点实验，后续用 `docker exec` 从容器内连接。若服务仍在启动，稍后重试 `PING`，直到返回 `PONG`。

```bash
redis_function_lab=$(docker run -d --rm --network none redis:7.4.2)
docker exec "$redis_function_lab" redis-server --version
docker exec "$redis_function_lab" redis-cli --version
docker exec "$redis_function_lab" redis-cli PING
```

首行声明 Lua 引擎和库名，`redis.register_function` 注册可调用的函数名。`redis-cli -x` 从标准输入读取库源码，作为 `FUNCTION LOAD` 的最后一个参数。加载成功返回 `inventory_lab`。

```bash
docker exec -i "$redis_function_lab" redis-cli -x FUNCTION LOAD <<'LUA'
#!lua name=inventory_lab
redis.register_function('reserve_one', function(keys, args)
    local stock = redis.call('GET', keys[1])
    if not stock or tonumber(stock) <= 0 then
        return -1
    end
    return redis.call('DECR', keys[1])
end)
LUA
```

**验证方法**　先确认库里注册了 `reserve_one`，再把样例 SKU 的库存设为 2。每条 `redis-cli` 命令使用新的连接。`FCALL` 中的 `1` 声明传入一个键，函数用 `keys[1]` 读取它，本例没有额外参数。

```bash
docker exec "$redis_function_lab" redis-cli FUNCTION LIST LIBRARYNAME inventory_lab
docker exec "$redis_function_lab" redis-cli SET lab:stock:sku42 2
docker exec "$redis_function_lab" redis-cli FCALL reserve_one 1 lab:stock:sku42
docker exec "$redis_function_lab" redis-cli FCALL reserve_one 1 lab:stock:sku42
docker exec "$redis_function_lab" redis-cli FCALL reserve_one 1 lab:stock:sku42
docker exec "$redis_function_lab" redis-cli GET lab:stock:sku42
docker exec "$redis_function_lab" redis-cli FCALL reserve_one 1 lab:stock:missing
docker exec "$redis_function_lab" redis-cli EXISTS lab:stock:missing
docker stop "$redis_function_lab"
```

`FUNCTION LIST` 应列出库 `inventory_lab`、引擎 `LUA` 和函数 `reserve_one`。`SET` 返回 `OK`。前三次扣减依次返回 `1`、`0`、`-1`，其中 `0` 表示成功扣完最后一件，`-1` 表示无库存。读取 SKU 的余量为 `0`。缺失的库存键也返回 `-1`，随后 `EXISTS` 返回 `0`，表示调用未创建键。停止容器后，`--rm` 清理容器和匿名卷。

2026-11-23 在 macOS 26.5 arm64、Docker Engine 27.3.1 和 Bash 3.2.57 上运行了以上三段命令。服务端与客户端均报告 7.4.2，`PING` 返回 `PONG`。加载返回 `inventory_lab`，列表显示 `LUA` 和 `reserve_one`。扣减结果依次为 `1`、`0`、`-1`，余量为 `0`。缺失键返回 `-1`，`EXISTS` 返回 `0`。停止后用 `docker container inspect` 和 `docker volume inspect` 确认实验容器与匿名卷均已删除。

**官方文档**　`FUNCTION LOAD`、`FUNCTION LIST` 和 `FCALL` 自 Redis 7.0 起可用，7.4.2 版上游测试包含函数库加载和跨连接调用使用的 API。 [Redis Functions](https://redis.io/docs/latest/develop/programmability/functions-intro/) · [FUNCTION LOAD](https://redis.io/docs/latest/commands/function-load/) · [FUNCTION LIST](https://redis.io/docs/latest/commands/function-list/) · [FCALL](https://redis.io/docs/latest/commands/fcall/) · [Redis 7.4.2 函数测试](https://raw.githubusercontent.com/redis/redis/7.4.2/tests/unit/functions.tcl)

### 哈希购物车数量更新

**适用场景**　购物车条目较少，应用需要按 SKU 设置或增减数量，也需要一次读取整车。每个购物车用一个 Redis Hash，字段名是 SKU，字段值是数量。`HINCRBY` 在服务端原子增减单个字段，适合并发修改同一 SKU。它允许负数，也会在重复请求时重复累加。需要数量下限或请求幂等时，应用须在写入流程中另外处理这些约束。`HGETALL` 的开销随字段数线性增长，适合这里的小购物车。

**接入步骤**　需要 Docker 和 Bash 或 Zsh。用 `redis:7.4.2` 启动独立容器，服务端与 `redis-cli` 均为 7.4.2，无需映射宿主机端口。若刚启动时连接失败，稍后重试 `PING`，直到返回 `PONG`。

```bash
redis_cart_lab=$(docker run -d --rm redis:7.4.2)
docker exec "$redis_cart_lab" redis-server --version
docker exec "$redis_cart_lab" redis-cli --version
docker exec "$redis_cart_lab" redis-cli PING
```

初始化两个 SKU，将键盘数量覆盖为 3，再移出一件。给尚未出现的集线器增加 1 件，最后读取整车。

```bash
docker exec "$redis_cart_lab" redis-cli HSET lab:cart:42 sku:keyboard 2 sku:mouse 1
docker exec "$redis_cart_lab" redis-cli HSET lab:cart:42 sku:keyboard 3
docker exec "$redis_cart_lab" redis-cli HINCRBY lab:cart:42 sku:keyboard -1
docker exec "$redis_cart_lab" redis-cli HINCRBY lab:cart:42 sku:hub 1
docker exec "$redis_cart_lab" redis-cli HGETALL lab:cart:42
```

**验证方法**　按执行顺序核对结果。`HGETALL` 按字段与值配对检查，字段顺序可以不同。

| 操作 | 预期结果 |
| --- | --- |
| 初始化两个 SKU | `HSET` 返回 `2`，表示新增两个字段。 |
| 覆盖键盘数量 | `HSET` 返回 `0`，表示没有新增字段。 |
| 键盘减 1 | `HINCRBY` 返回更新后的数量 `2`。 |
| 添加集线器 | 缺失字段从 `0` 起算，`HINCRBY` 返回 `1`。 |
| 读取整车 | `sku:keyboard` 为 `2`，`sku:mouse` 和 `sku:hub` 均为 `1`。 |

验证后停止容器，`--rm` 会自动删除容器和匿名卷。

```bash
docker stop "$redis_cart_lab"
```

2026-11-20 在 macOS 26.5 arm64、Docker Engine 27.3.1 上用 Bash 运行了上述命令。服务端和客户端均报告 7.4.2，`PING` 返回 `PONG`。两次 `HSET` 分别返回 `2` 和 `0`，两次 `HINCRBY` 分别返回 `2` 和 `1`。`HGETALL` 返回键盘 2 件、鼠标 1 件、集线器 1 件。停止后用 `docker container inspect "$redis_cart_lab"` 确认容器已删除。

**官方文档**　`HINCRBY` 支持有符号 64 位整数，7.4.2 标签下的官方测试覆盖 `HSET` 新增与覆盖、`HINCRBY` 增减和 `HGETALL` 读取。 [Redis Hash](https://redis.io/docs/latest/develop/data-types/hashes/) · [HSET](https://redis.io/docs/latest/commands/hset/) · [HINCRBY](https://redis.io/docs/latest/commands/hincrby/) · [HGETALL](https://redis.io/docs/latest/commands/hgetall/) · [7.4.2 版哈希测试](https://raw.githubusercontent.com/redis/redis/7.4.2/tests/unit/type/hash.tcl)

### HyperLogLog 独立访客估算

**适用场景**　活动看板需要统计每日和跨日独立访客，允许结果有统计误差。每天使用一个键，用同一种稳定的访客 ID 上报，跨日汇总时取并集。Redis HyperLogLog 每个键的结构最多约 12 KB，标准误差为 0.81%，实际误差会波动。需要保留成员名单或得到精确人数时，使用 Set 或从原始事件去重。

**接入步骤**　需要 Docker 和 Bash 或 Zsh。启动独立的 `redis:7.4.2` 容器，无需映射端口。确认服务端和 `redis-cli` 均为 7.4.2，等 `PING` 返回 `PONG` 后继续。

```bash
redis_uv_lab=$(docker run -d --rm redis:7.4.2)
docker exec "$redis_uv_lab" redis-server --version
docker exec "$redis_uv_lab" redis-cli --version
docker exec "$redis_uv_lab" redis-cli PING
```

第一天记录 alice、bob 和 carol，随后重放同一批访客。第二天记录 bob、carol 和 dave。用 `PFCOUNT` 分别查询单日和跨日访客，再用 `PFMERGE` 把两天的并集保存到新键。

```bash
docker exec "$redis_uv_lab" redis-cli PFADD lab:uv:day1 alice bob carol
docker exec "$redis_uv_lab" redis-cli PFADD lab:uv:day1 alice bob carol
docker exec "$redis_uv_lab" redis-cli PFADD lab:uv:day2 bob carol dave
docker exec "$redis_uv_lab" redis-cli PFCOUNT lab:uv:day1
docker exec "$redis_uv_lab" redis-cli PFCOUNT lab:uv:day2
docker exec "$redis_uv_lab" redis-cli PFCOUNT lab:uv:day1 lab:uv:day2
docker exec "$redis_uv_lab" redis-cli PFMERGE lab:uv:total lab:uv:day1 lab:uv:day2
docker exec "$redis_uv_lab" redis-cli PFCOUNT lab:uv:total
```

**验证方法**　按顺序核对这组固定输入的结果。跨日并集只计一次同时出现在两天的 bob 和 carol。

| 操作 | 预期结果 |
| --- | --- |
| 第一天写入、重放，第二天写入 | 依次返回 `1`、`0`、`1`。`PFADD` 的返回值表示内部状态是否改变。 |
| 分别查询两个单日键 | 均返回 `3`。 |
| 同时查询两个单日键 | 返回 `4`。 |
| 合并并查询汇总键 | 依次返回 `OK`、`4`。 |

验证后停止容器，`--rm` 会自动删除容器和匿名卷。

```bash
docker stop "$redis_uv_lab"
```

2026-11-20 在 macOS 26.5 arm64、Docker Engine 27.3.1 上用 Bash 运行了上述全部命令。服务端与客户端均报告 7.4.2，`PING` 返回 `PONG`。重复写入返回 0，每天均估算出 3 位访客，两种跨日查询均得到 4。停止后用 `docker container inspect "$redis_uv_lab"` 确认容器已删除。

**官方文档**　以下三个命令从 Redis 2.8.9 起可用。7.4.2 标签下的官方测试覆盖重复写入、基数估算和合并。 [HyperLogLog](https://redis.io/docs/latest/develop/data-types/probabilistic/hyperloglogs/) · [PFADD](https://redis.io/docs/latest/commands/pfadd/) · [PFCOUNT](https://redis.io/docs/latest/commands/pfcount/) · [PFMERGE](https://redis.io/docs/latest/commands/pfmerge/) · [7.4.2 版 HyperLogLog 测试](https://raw.githubusercontent.com/redis/redis/7.4.2/tests/unit/hyperloglog.tcl)

### 有序集合积分排行

**适用场景**　活动积分榜需要按分数取前几名，也需要按用户查询名次。用用户 ID 作为有序集合的成员，积分作为分值。重复提交同一加分事件会重复累加，应用应按事件 ID 去重。如果业务规定同分时按达成时间排序，需要另外设计排序规则。

**接入步骤**　需要 Docker 和 Bash 或 Zsh。用 `redis:7.4.2` 镜像内同版本的服务端和 `redis-cli`，容器无需映射端口。启动后确认两者均为 7.4.2，并等待 `PING` 返回 `PONG`。

```bash
redis_rank_lab=$(docker run -d --rm redis:7.4.2)
docker exec "$redis_rank_lab" redis-server --version
docker exec "$redis_rank_lab" redis-cli --version
docker exec "$redis_rank_lab" redis-cli PING
```

用 `ZADD` 写入三个成员的初始积分。用 `ZRANGE ... REV WITHSCORES` 查前两名，区间的下标从 0 开始，且包含结束位置。积分相同时，`REV` 按成员字符串的逆字典序返回。随后用 `ZINCRBY` 给 alice 加 15 分，再查榜单和个人名次。

```bash
docker exec "$redis_rank_lab" redis-cli --json ZADD lab:leaderboard 90 alice 100 bob 100 carol
docker exec "$redis_rank_lab" redis-cli --json ZRANGE lab:leaderboard 0 1 REV WITHSCORES
docker exec "$redis_rank_lab" redis-cli --json ZINCRBY lab:leaderboard 15 alice
docker exec "$redis_rank_lab" redis-cli --json ZRANGE lab:leaderboard 0 1 REV WITHSCORES
docker exec "$redis_rank_lab" redis-cli --json ZREVRANK lab:leaderboard alice
docker exec "$redis_rank_lab" redis-cli --json ZREVRANK lab:leaderboard dave
```

**验证方法**　按顺序核对下表。将 `ZREVRANK` 返回的下标加 1 即可得到面向用户展示的名次，缺少成员时按未上榜处理。

| 操作 | 预期结果 |
| --- | --- |
| 初始化 | `3`，表示新增三个成员 |
| 查询前两名 | `[["carol",100],["bob",100]]` |
| alice 加分 | `105`，表示更新后的积分 |
| 再查前两名 | `[["alice",105],["carol",100]]` |
| 查询 alice 和 dave 的名次 | 分别返回 `0` 和 `null` |

验证结束后停止容器，`--rm` 会自动清理容器和匿名卷。

```bash
docker stop "$redis_rank_lab"
```

2026-11-20 在 macOS 26.5 arm64、Docker Engine 27.3.1 上用 Bash 运行上述命令。服务端和客户端均报告 7.4.2，`PING` 返回 `PONG`。初次查询中同为 100 分的 carol 排在 bob 前面。alice 加分后以 105 分升至榜首，个人名次查询返回下标 0，dave 返回 `null`。停止后确认容器已自动删除。

**官方文档**　[有序集合](https://redis.io/docs/latest/develop/data-types/sorted-sets/) · [ZADD](https://redis.io/docs/latest/commands/zadd/) · [ZINCRBY](https://redis.io/docs/latest/commands/zincrby/) · [ZRANGE](https://redis.io/docs/latest/commands/zrange/) · [ZREVRANK](https://redis.io/docs/latest/commands/zrevrank/)。这些命令均适用于 7.4.2，其中 `ZRANGE` 的 `REV` 选项从 6.2 起可用。

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

### 缓存内存上限与键淘汰

**适用场景**　商品详情可以从源库重建，缓存达到内存上限时可以腾出空间继续写入。对缓存键都设置 TTL 时，可用 `volatile-lru` 从带 TTL 的键中近似按最近最少使用淘汰。无 TTL 的键不参与这种淘汰。如果没有可淘汰的键，仍可能拒绝写入。需要保留现有键并让应用处理写入失败时，可以选择 `noeviction`。`maxmemory` 是 Redis 的淘汰阈值，给容器或主机分配内存时还要为 Redis 的其他开销留出余量。

**接入步骤**　需要 Docker 和 Bash 或 Zsh。用 `redis:7.4.2` 启动独立容器，使用镜像内同版本的服务端和 `redis-cli`。重试 `PING` 直到返回 `PONG`。本例需要执行 `CONFIG SET` 的权限，配置即时生效，仅用于这个实验实例。

```bash
redis_eviction_lab=$(docker run -d --rm --network none redis:7.4.2)
redis_eviction_cli() { docker exec "$redis_eviction_lab" redis-cli "$@"; }
docker exec "$redis_eviction_lab" redis-server --version
redis_eviction_cli --version
redis_eviction_cli PING
```

**验证方法**　先写入无 TTL 的店铺标记以及带一小时 TTL 的商品缓存。用 `SETRANGE` 把商品缓存扩到 4 MiB，返回的新长度为 `4194304`。随后将上限降到 3 MiB，以便在同一份数据上对比两种策略。

```bash
redis_eviction_cli SET lab:shop:region east
redis_eviction_cli SET lab:cache:sku42 x EX 3600
redis_eviction_cli SETRANGE lab:cache:sku42 4194303 x
redis_eviction_cli CONFIG SET maxmemory-policy noeviction maxmemory 3145728
redis_eviction_cli SET lab:cache:sku43 fresh EX 3600
redis_eviction_cli TTL lab:cache:sku42
redis_eviction_cli TTL lab:cache:sku43
redis_eviction_cli INFO stats | grep -E '^(evicted_keys|expired_keys):'
```

`noeviction` 下写入返回 `OOM`。旧缓存的 TTL 为正数，新缓存的 TTL 查询返回 `-2`，表示键不存在。淘汰和过期计数均为 `0`。

```bash
redis_eviction_cli CONFIG SET maxmemory-policy volatile-lru
redis_eviction_cli SET lab:cache:sku43 fresh EX 3600
redis_eviction_cli TTL lab:cache:sku42
redis_eviction_cli GET lab:cache:sku43
redis_eviction_cli GET lab:shop:region
redis_eviction_cli INFO stats | grep -E '^(evicted_keys|expired_keys):'
docker stop "$redis_eviction_lab"
```

切换策略后写入返回 `OK`，旧缓存是唯一带 TTL 的淘汰候选，它的 TTL 查询现在返回 `-2`。新缓存值为 `fresh`，无 TTL 的店铺标记仍为 `east`。`evicted_keys` 增至 `1`，`expired_keys` 仍为 `0`，说明旧键因内存压力被移除。停止容器后 Docker 会自动清理容器和匿名卷。

2026-11-23 在 macOS 26.5 arm64、Docker Engine 27.3.1 和 Bash 3.2.57 上执行了以上三段命令。服务端和客户端均报告 7.4.2，`PING` 返回 `PONG`。第一次写入得到 `OOM`，旧缓存 TTL 为 `3600`，新缓存键不存在，淘汰和过期计数均为 `0`。切换策略后写入成功，旧缓存的 TTL 查询为 `-2`，新缓存和店铺标记分别读到 `fresh` 和 `east`，淘汰计数为 `1`，过期计数为 `0`。用 `docker container inspect` 和 `docker volume inspect` 确认实验容器和匿名卷均已清理。

**官方文档**　策略以 [Redis 7.4.2 配置文件](https://raw.githubusercontent.com/redis/redis/7.4.2/redis.conf)为准，选键和监测方式见[键淘汰](https://redis.io/docs/latest/develop/reference/eviction/)。示例中的命令参数见 [SET](https://redis.io/docs/latest/commands/set/)、[SETRANGE](https://redis.io/docs/latest/commands/setrange/)、[CONFIG SET](https://redis.io/docs/latest/commands/config-set/)、[TTL](https://redis.io/docs/latest/commands/ttl/) 和 [INFO](https://redis.io/docs/latest/commands/info/)。

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

### 发布订阅与缓存失效通知

**适用场景**　多个应用实例各自维护进程内的商品缓存。源数据更新后，向在线实例广播商品标识，订阅端收到通知后删除对应的本地缓存，下次读取时重新加载。Redis Pub/Sub 是至多一次投递，适合能用 TTL、重连后清理缓存等措施兜底的通知。需要追踪处理结果或恢复离线期间的消息时，可使用 [Streams 消费组](#streams-消费组确认与接管)。

**接入步骤**　需要 Docker 和两个终端。本例用 `redis:7.4.2` 镜像，服务端和 `redis-cli` 均为 7.4.2。容器不映射宿主机端口。先在终端 A 启动独立实例，确认两个版本输出和 `PONG`。若服务还在启动，稍后重试 `PING`。

```bash
docker run -d --rm --name redis-pubsub-lab redis:7.4.2
docker exec redis-pubsub-lab redis-server --version
docker exec redis-pubsub-lab redis-cli --version
docker exec redis-pubsub-lab redis-cli PING
```

在 A 启动订阅，等到客户端显示 `subscribe`、频道名和订阅数 1。

```bash
docker exec -it redis-pubsub-lab redis-cli SUBSCRIBE lab:cache:invalidate
```

在终端 B 发布第一条通知。观察 A 收到消息后，在 A 按 Ctrl+C 结束订阅，再在 B 发布第二条通知。

```bash
# A 在线
docker exec redis-pubsub-lab redis-cli PUBLISH lab:cache:invalidate product:1001
# A 退出后
docker exec redis-pubsub-lab redis-cli PUBLISH lab:cache:invalidate product:1002
```

在 A 重新运行订阅命令，等到订阅确认，再在 B 发布第三条通知。

```bash
docker exec redis-pubsub-lab redis-cli PUBLISH lab:cache:invalidate product:1003
```

**验证方法**　分别核对发布返回值和订阅端输出。`PUBLISH` 在本实验的单机实例上返回服务端发往的客户端数，订阅端的缓存处理结果需要由应用自行记录。

| 阶段 | B 的 `PUBLISH` 返回值 | A 的订阅输出 |
| --- | --- | --- |
| 在线 | 1 | 收到 `message`、频道名和 `product:1001`。 |
| 退出后 | 0 | 此时没有订阅连接。 |
| 重新订阅并发布 | 1 | 先看到订阅确认，随后收到 `product:1003`，期间没有收到 `product:1002`。 |

在 A 按 Ctrl+C 退出，在 B 执行 `docker stop redis-pubsub-lab`。启动时指定的 `--rm` 会自动删除容器。

2026-11-20 在 macOS 26.5 arm64、Docker Engine 27.3.1 上运行上述命令。服务端和客户端均报告 7.4.2，`PING` 返回 `PONG`。三次 `PUBLISH` 依次返回 1、0、1，两次 `SUBSCRIBE` 均返回订阅确认，订阅端依次收到 `product:1001` 和 `product:1003`。停止后确认容器已自动删除。

**官方文档**　命令文档列出语法和返回值，Redis 7.4.2 的官方测试包含订阅确认及有无订阅者时的发布断言。 [Redis Pub/Sub](https://redis.io/docs/latest/develop/pubsub/) · [SUBSCRIBE](https://redis.io/docs/latest/commands/subscribe/) · [PUBLISH](https://redis.io/docs/latest/commands/publish/) · [7.4.2 版发布订阅测试](https://raw.githubusercontent.com/redis/redis/7.4.2/tests/unit/pubsub.tcl)

### 键空间通知与缓存过期观察

**适用场景**　调试商品缓存时，可以订阅 Redis 自动产生的过期通知，观察某个商品键何时被移除，或记录本实例被过期清理的键名。Redis 在访问过期键或后台扫描并删除过期键时产生 `expired`，通知可能晚于 TTL 归零。通知通过 Pub/Sub 投递，断线期间的事件无法重放。需要可靠关单或精确调度时，使用有持久化和补偿机制的任务方案。

**接入步骤**　需要 Bash、C 编译器、GNU Make 和两个终端。解压 [Redis 7.4.2 官方源码](https://codeload.github.com/redis/redis/tar.gz/refs/tags/7.4.2)，从包含 `redis-7.4.2/` 的目录在终端 A 执行。服务端和客户端均应显示 7.4.2。实例仅监听临时目录内的 Unix Socket。

```bash
make -C redis-7.4.2/src -j8 MALLOC=libc redis-server redis-cli
redis_bin=$(cd redis-7.4.2/src && pwd)
"$redis_bin/redis-server" --version
"$redis_bin/redis-cli" --version
lab_dir=$(mktemp -d /tmp/redis-notify.XXXXXX)
lab_cli() { "$redis_bin/redis-cli" -s "$lab_dir/redis.sock" "$@"; }

"$redis_bin/redis-server" --port 0 --unixsocket "$lab_dir/redis.sock" \
  --dir "$lab_dir" --save "" --appendonly no \
  --notify-keyspace-events KEx --logfile "$lab_dir/redis.log" &
redis_pid=$!
lab_cli PING
```

收到 `PONG` 后继续。若首次连接失败，稍后重试 `lab_cli PING`。通知默认关闭，启动参数中的 `K` 启用按键订阅的频道，`E` 启用按事件订阅的频道，`x` 只启用过期事件。

**验证方法**　先在终端 A 运行 `declare -p redis_bin lab_dir`，将两行变量定义复制到终端 B 执行，以便两个客户端连接同一个 Socket。然后在终端 A 执行下列订阅命令，等待两条 `subscribe` 确认。

```bash
lab_cli --csv SUBSCRIBE '__keyspace@0__:lab:product:42' '__keyevent@0__:expired'
```

在终端 B 写入一个 2 秒后过期的键，预期返回 `OK`。

```bash
"$redis_bin/redis-cli" -s "$lab_dir/redis.sock" SET lab:product:42 cached EX 2
```

等待终端 A 出现以下两条消息。按键订阅收到事件名，按事件订阅收到键名。频道名中的 `0` 对应本例使用的数据库。

```text
"message","__keyspace@0__:lab:product:42","expired"
"message","__keyevent@0__:expired","lab:product:42"
```

在终端 B 查询该键，预期返回 `(integer) 0`。

```bash
"$redis_bin/redis-cli" -s "$lab_dir/redis.sock" EXISTS lab:product:42
```

在终端 A 按 Ctrl-C 退出订阅，再清理临时实例。

```bash
lab_cli SHUTDOWN NOSAVE
wait "$redis_pid"
rm -r "$lab_dir"
```

**实测记录**　在 macOS 26.5（arm64）用 Apple Clang 21.0.0 和 GNU Make 3.81 执行上述 `make` 命令。两端版本均为 7.4.2，`PING` 返回 `PONG`。用两个独立客户端执行 `SUBSCRIBE` 和 `SET ... EX 2`，收到两条订阅确认后写入返回 `OK`。在查询键之前收到上面列出的两条过期消息，随后 `EXISTS` 返回 0。`SHUTDOWN NOSAVE` 后服务端以状态码 0 退出。

**官方文档**　[Redis 键空间通知](https://redis.io/docs/latest/develop/pubsub/keyspace-notifications/)说明频道、消息和过期时机。[Redis 7.4.2 配置文件](https://github.com/redis/redis/blob/7.4.2/redis.conf#L1880-L1930)列出本版本支持的标志。

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

### RDB 快照与重启恢复

**适用场景**　为可从订单流水重建的库存读模型保留一个时间点的副本，以便实例重启后快速恢复。仅启用 RDB 时，恢复的数据取决于最后一份成功写入磁盘的快照，之后已确认的写入可能丢失。适用于业务能接受这一恢复点、或能从原始记录补齐差额的场景。示例用同步 `SAVE` 固定快照时点，它会阻塞其他客户端，在线服务通常用后台快照 `BGSAVE`。

**接入步骤**　需要 Bash、C 编译器、GNU Make 和 Redis Open Source 7.4.2 源码。解压官方源码后，在包含 `redis-7.4.2/` 的目录执行以下命令。本例将数据和日志写入同一个临时目录，仅通过 Unix Socket 连接。启动参数关闭自动快照和 AOF，以便单独观察手动快照的效果。

```bash
make -C redis-7.4.2/src -j8 MALLOC=libc redis-server redis-cli
redis_bin=$(cd redis-7.4.2/src && pwd)
"$redis_bin/redis-server" --version
"$redis_bin/redis-cli" --version
lab_dir=$(mktemp -d /tmp/redis-rdb.XXXXXX)
rdb_cli() { "$redis_bin/redis-cli" -s "$lab_dir/redis.sock" "$@"; }
start_redis() {
  "$redis_bin/redis-server" --port 0 --unixsocket "$lab_dir/redis.sock" \
    --dir "$lab_dir" --dbfilename dump.rdb --save "" --appendonly no \
    --logfile "$lab_dir/redis.log" &
  redis_pid=$!
}

start_redis
rdb_cli PING
```

两个版本输出均应包含 `7.4.2`。服务刚启动时，重试 `rdb_cli PING` 直至返回 `PONG`。

**验证方法**　先写入库存并保存快照，再扣减一件。`SET` 和 `SAVE` 应各返回 `OK`，`test -s` 退出状态为 0，表示快照文件非空。扣减和随后的读取各返回 `9`。

```bash
rdb_cli SET lab:stock:sku42 10
rdb_cli SAVE
test -s "$lab_dir/dump.rdb"
rdb_cli DECR lab:stock:sku42
rdb_cli GET lab:stock:sku42
```

用 `SHUTDOWN NOSAVE` 跳过退出时的保存，等待进程退出后使用同一目录启动。若尚未就绪，重试 `PING`，直到返回 `PONG`，再读取库存和检查加载日志。

```bash
rdb_cli SHUTDOWN NOSAVE
wait "$redis_pid"
start_redis
rdb_cli PING
```

```bash
rdb_cli GET lab:stock:sku42
grep -F 'Done loading RDB, keys loaded: 1' "$lab_dir/redis.log"
rdb_cli SHUTDOWN NOSAVE
wait "$redis_pid"
rm -r "$lab_dir"
test ! -e "$lab_dir"
```

库存恢复为 `10`，日志显示从 RDB 加载了 1 个键。最后停掉实验实例并删除其临时目录。

2026-11-23 在 macOS 26.5 arm64、Bash 3.2.57 上用官方 7.4.2 源码编译并运行了上述实验。服务端和客户端版本均为 7.4.2，两次启动均收到 `PONG`。`SAVE` 返回 `OK` 并生成非空的 `dump.rdb`，重启前读取为 `9`，重启后读取为 `10`，日志显示加载 1 个键且无过期键。最后一次 `wait` 和临时目录删除检查均通过。

**官方文档**　持久化指南解释快照与恢复边界，7.4.2 的配置文件包含本例使用的启动配置。 [Redis 持久化](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/) · [SAVE](https://redis.io/docs/latest/commands/save/) · [SHUTDOWN](https://redis.io/docs/latest/commands/shutdown/) · [7.4.2 配置文件](https://raw.githubusercontent.com/redis/redis/7.4.2/redis.conf) · [7.4.2 源码归档](https://codeload.github.com/redis/redis/tar.gz/refs/tags/7.4.2)

### AOF 写入日志与重启恢复

**适用场景**　库存读模型频繁更新，希望实例重启后恢复最近的写入。AOF 持续记录修改数据的操作，启动时加载基准文件并重放增量日志。默认的 `appendfsync everysec` 通常每秒同步一次，故障时可能丢失约一秒的写入。`always` 更频繁地同步，代价是写入延迟和吞吐。本例用 `always` 观察正常重启恢复，适用于能接受同步写入开销的本地演练。

**接入步骤**　需要 Bash、C 编译器、GNU Make 和 Redis Open Source 7.4.2。解压[官方源码](https://codeload.github.com/redis/redis/tar.gz/refs/tags/7.4.2)后，在包含 `redis-7.4.2/` 的目录运行以下命令。实验使用独立临时目录和 Unix Socket，关闭自动 RDB 快照，保留 AOF 默认的 RDB 格式基准文件。

```bash
make -C redis-7.4.2/src -j8 MALLOC=libc redis-server redis-cli
redis_bin=$(cd redis-7.4.2/src && pwd)
"$redis_bin/redis-server" --version
"$redis_bin/redis-cli" --version
lab_dir=$(mktemp -d /tmp/redis-aof.XXXXXX)
aof_cli() { "$redis_bin/redis-cli" -s "$lab_dir/redis.sock" "$@"; }
start_redis() {
  "$redis_bin/redis-server" --port 0 --unixsocket "$lab_dir/redis.sock" \
    --dir "$lab_dir" --save "" --appendonly yes --appendfsync always \
    --appenddirname appendonlydir --logfile "$lab_dir/redis.log" &
  redis_pid=$!
}

start_redis
```

服务端和客户端的版本输出应包含 `7.4.2`。运行 `aof_cli PING`，等到返回 `PONG` 后继续。

**验证方法**　先写入库存 10，再扣减一件。预期 `SET` 返回 `OK`，`DECR` 返回 `9`，`INFO persistence` 显示 `aof_enabled:1` 和 `aof_last_write_status:ok`。清单应列出基准文件和增量文件。两项 `test` 均应以状态码 0 退出，表示增量日志非空且没有独立的 `dump.rdb` 快照。

```bash
aof_cli SET lab:stock:sku42 10
aof_cli DECR lab:stock:sku42
aof_cli INFO persistence | grep -E '^(aof_enabled|aof_last_write_status):'
cat "$lab_dir/appendonlydir/appendonly.aof.manifest"
test -s "$lab_dir/appendonlydir/appendonly.aof.1.incr.aof"
test ! -e "$lab_dir/dump.rdb"
```

用 `SHUTDOWN NOSAVE` 正常停机并跳过退出时的独立 RDB 快照。保留整个数据目录，再次启动 Redis。

```bash
aof_cli SHUTDOWN NOSAVE
wait "$redis_pid"
start_redis
```

再次运行 `aof_cli PING`，等到返回 `PONG`。库存应为 `9`，日志应显示从增量文件加载数据。实验结束后关闭实例并删除临时目录。

```bash
aof_cli GET lab:stock:sku42
grep -F 'DB loaded from incr file appendonly.aof.1.incr.aof' "$lab_dir/redis.log"
aof_cli SHUTDOWN NOSAVE
wait "$redis_pid"
rm -r "$lab_dir"
test ! -e "$lab_dir"
```

2026-11-23 在 macOS 26.5 arm64、Bash 3.2.57 上用官方源码执行了上述构建、写入和重启步骤。服务端和客户端均为 7.4.2，两次启动均返回 `PONG`。`SET` 返回 `OK`，扣减和重启后读取均返回 `9`。`INFO persistence` 的两个字段与预期一致，清单列出基准文件和增量文件。增量日志非空，独立的 `dump.rdb` 未生成，重启日志确认加载了增量文件。最后一次退出和临时目录删除检查均通过。

**官方文档**　[Redis 持久化](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/) · [7.4.2 配置文件](https://raw.githubusercontent.com/redis/redis/7.4.2/redis.conf) · [INFO](https://redis.io/docs/latest/commands/info/) · [SHUTDOWN](https://redis.io/docs/latest/commands/shutdown/)

### 主从复制与副本只读验证

**适用场景**　库存展示页可以从副本读取数据，扣减库存和需要立即读到最新结果的操作走主节点。Redis 默认异步复制，副本读数可能短时滞后，故障时也可能丢失主节点已经确认的写入。需要自动故障转移时，还需配置 Sentinel 或 Cluster。

**接入步骤**　需要 Bash、C 编译器、GNU Make 和空闲的本地 TCP 端口 `16379`。解压 [Redis 7.4.2 官方源码](https://codeload.github.com/redis/redis/tar.gz/refs/tags/7.4.2)，从包含 `redis-7.4.2/` 的目录运行以下命令。两个实例使用独立临时目录，主节点只监听回环地址，副本只通过 Unix Socket 接收客户端连接。以下命令在同一个 Bash 会话执行。

```bash
make -C redis-7.4.2/src -j8 MALLOC=libc redis-server redis-cli
redis_bin=$(cd redis-7.4.2/src && pwd)
"$redis_bin/redis-server" --version
"$redis_bin/redis-cli" --version
lab_dir=$(mktemp -d /tmp/redis-repl.XXXXXX)
mkdir "$lab_dir/primary" "$lab_dir/replica"

primary_cli() { "$redis_bin/redis-cli" -s "$lab_dir/primary.sock" "$@"; }
replica_cli() { "$redis_bin/redis-cli" -s "$lab_dir/replica.sock" "$@"; }

"$redis_bin/redis-server" --bind 127.0.0.1 --port 16379 \
  --unixsocket "$lab_dir/primary.sock" --dir "$lab_dir/primary" \
  --save "" --appendonly no --logfile "$lab_dir/primary.log" &
primary_pid=$!
```

服务端和客户端都应显示 7.4.2。运行 `primary_cli PING`，等到返回 `PONG` 后先在主节点写入库存，预期返回 `OK`，然后启动副本。用 `--replicaof` 指定主节点的地址和端口。

```bash
primary_cli SET lab:stock:sku42 10
"$redis_bin/redis-server" --port 0 \
  --unixsocket "$lab_dir/replica.sock" --dir "$lab_dir/replica" \
  --save "" --appendonly no --logfile "$lab_dir/replica.log" \
  --replicaof 127.0.0.1 16379 --replica-read-only yes &
replica_pid=$!
```

**验证方法**　重复运行以下两条命令，直到看到 `master_link_status:up`、`master_sync_in_progress:0` 和库存 `10`。如果一直不满足，查看 `"$lab_dir/replica.log"` 检查连接或同步错误。Redis 7.4.2 的复制信息仍使用 `role:slave` 和 `slave_read_only:1` 表示只读副本。

```bash
replica_cli INFO replication | grep -E '^(role|master_link_status|master_sync_in_progress|slave_read_only):'
replica_cli GET lab:stock:sku42
```

在主节点扣减一件，预期返回 `9`。重复读取副本直到返回 `9`，确认首次同步完成后主节点的写入仍会传播。随后直接向副本写入，预期返回 `READONLY` 错误，两个节点的库存都仍为 `9`。

```bash
primary_cli DECR lab:stock:sku42
replica_cli GET lab:stock:sku42
replica_cli SET lab:stock:sku42 99
primary_cli GET lab:stock:sku42
replica_cli GET lab:stock:sku42
```

结束后关闭副本和主节点，清理实验目录。

```bash
replica_cli SHUTDOWN NOSAVE
wait "$replica_pid"
primary_cli SHUTDOWN NOSAVE
wait "$primary_pid"
rm -r "$lab_dir"
test ! -e "$lab_dir"
```

2026-11-23 在 macOS 26.5（arm64）、Bash 3.2.57 上执行了上述命令。用 `make -C redis-7.4.2/src -j8 MALLOC=libc redis-server redis-cli` 构建的两个二进制均报告 7.4.2。主节点 `PING` 返回 `PONG`，写入返回 `OK`。副本的 `INFO replication` 显示连接正常、同步结束及只读状态，首次读取返回 `10`。主节点执行 `DECR` 后，副本读取返回 `9`。副本执行 `SET` 返回 `READONLY`，两端重读均为 `9`。两个实例正常退出，临时目录删除检查通过。

**官方文档**　[Redis 复制](https://redis.io/docs/latest/operate/oss_and_stack/management/replication/) · [Redis 7.4.2 配置文件](https://raw.githubusercontent.com/redis/redis/7.4.2/redis.conf) · [INFO](https://redis.io/docs/latest/commands/info/)

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
