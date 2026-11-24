# Debezium Engine + PostgreSQL 读取订单 CDC 事件

## 适用场景

搜索索引或统计服务需要知道一笔订单何时新增、改状态或删除。PostgreSQL 将已提交的数据变更写入 WAL。Debezium 的 PostgreSQL Connector 将初始快照和之后的变更整理成同一类记录，Java 应用在回调中读取事件类型、主键和行值。

| 组件 | 分工 |
| --- | --- |
| [PostgreSQL](../../docs/components/data-pipelines.md#postgresql-logical-replication) | 通过 `pgoutput`、Publication 和逻辑复制槽提供表变更。 |
| [Debezium](../../docs/components/data-pipelines.md#debezium) | 读取存量快照，持续消费逻辑复制数据并生成结构化 `SourceRecord`。 |
| Java 消费端 | 从 `DebeziumEngine` 回调读取 `op`、事件主键以及 `before` 和 `after`。 |

## 接入步骤

1. 在发布端启用 `wal_level=logical`，为订单表创建 Publication。实验表使用整数主键，并启用 `REPLICA IDENTITY FULL` 以观察完整的更新前值和删除前值。接入实际表时，根据消费端所需字段评估复制标识和 WAL 开销。
2. 加入 `io.debezium:debezium-embedded` 与 `io.debezium:debezium-connector-postgres`。设置数据库地址、端口、用户、`plugin.name=pgoutput`、`slot.name` 和已有的 `publication.name`。用 `table.include.list` 限定订单表，并用持久化 offset 存储记录恢复位置。实验使用临时的 `FileOffsetBackingStore` 文件。
3. 启动 `DebeziumEngine` 并注册消费者。收到 `r` 或 `c` 时从 `after` 读取行。收到 `u` 时可比较 `before` 和 `after`。收到 `d` 时，根据主键或 `before` 删除下游投影。本实验将 `tombstones.on.delete` 设为 `false`，只观察带 `op=d` 的删除事件。

## 本地验证

需要 JDK 17+ 和可下载 Maven 依赖的网络。从仓库根目录运行。测试启动一个真实 PostgreSQL 实例和 Java 进程内的 Debezium Engine。

```sh
./mvnw -B -f labs/debezium-postgres-events/pom.xml clean verify
```

| 输入 | 预期事件 |
| --- | --- |
| 启动前已有订单 41 | `r`，`after.status=PAID` |
| 新建订单 42 | `c`，`after.status=NEW` |
| 将订单 42 更新为已支付 | `u`，`before.status=NEW`，`after.status=PAID` |
| 删除订单 42 | `d`，`before.status=PAID`，`after=null` |

实验核对快照、真实提交和事件内容。业务消费端应为下游写入定义幂等语义，并在目标环境演练 offset 恢复和复制槽的 WAL 保留。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、JDK 17.0.16 上运行上述 Maven 命令，使用 Debezium 3.2.7.Final 和 PostgreSQL 17.12。1 项集成测试通过，记录依次为 `r 41`、`c 42`、`u 42` 和 `d 42`。更新记录保留旧状态 `NEW` 与新状态 `PAID`，删除记录保留旧状态 `PAID`，新值为空。

## 官方资料

- [Debezium Engine 3.2](https://debezium.io/documentation/reference/3.2/development/engine.html) · [Debezium 官方示例](https://github.com/debezium/debezium-examples)
- [PostgreSQL Connector 3.2](https://debezium.io/documentation/reference/3.2/connectors/postgresql.html) · [PostgreSQL 复制标识](https://www.postgresql.org/docs/17/logical-replication-publication.html)
