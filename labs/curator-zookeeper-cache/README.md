# Apache Curator + ZooKeeper 监听门店接单上限

## 适用场景

运营把门店接单上限从 5 调到 8，订单应用需要更新本地读取到的配置。ZooKeeper 保存配置的当前值和版本。Curator 在应用内维护该节点的本地缓存，并在节点变化时通知应用。

| 组件 | 分工 |
| --- | --- |
| [ZooKeeper](../../docs/components/service-tools.md#zookeeper) | 通过 znode 保存门店配置，用节点版本约束修改和删除。 |
| [Apache Curator](../../docs/components/service-tools.md#apache-curator) | 管理客户端会话，通过 CuratorCache 初始加载并监听单个节点。 |

## 最小接入

1. Java 应用声明 `org.apache.curator:curator-recipes:5.9.0`，在连接字符串指向实际 ZooKeeper 端点后创建并启动 `CuratorFramework`。CuratorCache 需要 ZooKeeper 3.6 或更高。
2. 管理端写入 `/stores/42/order-limit`。应用端为这一节点创建 `CuratorCache`，选择 `SINGLE_NODE_CACHE`，在 `start()` 前注册初始化、修改和删除监听。
3. 初始化完成后用 `cache.get(path)` 读取配置快照。监听到修改后可刷新本地业务配置，监听到删除后按业务定义处理缺失值。示例中两个独立会话经 ZooKeeper 传递变化，查询走应用端的本地缓存。

CuratorCache 适合读取当前快照。需要逐次保留变更和审计时，应走有明确保留规则的事件记录。应用关闭时先关缓存，再关客户端。

## 本地验证

从仓库根目录执行。需要 JDK 17+ 和可下载 Maven 依赖的网络。

```sh
./mvnw -B -f labs/curator-zookeeper-cache/pom.xml clean verify
```

测试用 `curator-test` 在回环端口启动真实 ZooKeeper，创建两个独立的 Curator 会话。管理端创建值 5，应用端等待初始化后读到 5。管理端以版本 0 将值改为 8，修改事件的版本为 1，缓存读到 8。管理端再以版本 1 删除节点，应用端收到删除事件，缓存读到空值。测试覆盖单节点和已连接会话中的变更，重连、集群切换和访问控制由部署环境另行验证。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、JDK 17.0.16 上运行上述 Maven 命令，使用 Apache Curator 5.9.0 和 ZooKeeper 3.9.3。1 项集成测试通过。门店 42 的本地接单上限从 5 更新到 8，删除后缓存不再含有该节点。

## 官方资料

- [Apache Curator 5.9.0](https://github.com/apache/curator/releases/tag/apache-curator-5.9.0) · [Curator 接入示例](https://curator.apache.org/docs/getting-started/) · [CuratorCache](https://curator.apache.org/docs/recipes-curator-cache/)
- [ZooKeeper 3.9.3 Watch 与版本](https://zookeeper.apache.org/doc/r3.9.3/zookeeperProgrammers.html#ch_zkWatches)
