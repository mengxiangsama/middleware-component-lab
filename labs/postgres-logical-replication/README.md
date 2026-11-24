# PostgreSQL 逻辑复制订单表

## 适用场景

订单库只需把一张订单表同步到报表库。先复制已有订单，再把源端提交的新增、编辑和删除应用到目标表。订阅端暂停处理期间，发布端的逻辑复制槽保留它继续追赶所需的 WAL。本实验从发布表和订阅表的逐行结果验证链路。

| 组件 | 分工 |
| --- | --- |
| [PostgreSQL 发布端](../../docs/components/data-pipelines.md#postgresql-logical-replication) | 用 Publication 选择同步的表，用逻辑复制槽记录消费进度。 |
| PostgreSQL 订阅端 | 复制初始数据，使用 Subscription 接收发布端变更并更新本地表。 |
| Java JDBC | 从两端执行配置和验证 SQL，按主键核对目标订单。 |

## 接入步骤

1. 发布端设置 `wal_level=logical` 后重启，并为会话预留 WAL sender 与复制槽。订阅端确保有可用的逻辑复制 worker。应用到现有环境时，单独为复制连接配置认证及 [必需权限](https://www.postgresql.org/docs/17/logical-replication-security.html)。
2. 在两个库创建同名的表 `public.demo_orders`，为 `order_id` 设置主键。主键用于定位目标端的更新和删除。发布端执行 `CREATE PUBLICATION demo_orders_pub FOR TABLE public.demo_orders`。
3. 在订阅端先确认目标表为空，再使用发布库连接参数执行 `CREATE SUBSCRIPTION demo_orders_sub CONNECTION '…' PUBLICATION demo_orders_pub`。默认会复制已有行。实验分别启动两个独立的本地 PostgreSQL，使用临时端口和数据库目录。
4. 在订阅端用 `ALTER SUBSCRIPTION demo_orders_sub DISABLE` 暂停处理。确认发布端 `pg_replication_slots` 里的同名复制槽仍存在且 `active=false` 后，在发布端写入新订单。执行 `ALTER SUBSCRIPTION demo_orders_sub ENABLE`，等订阅表读到这笔订单。运行较久的暂停需要关注源端 WAL 磁盘和复制槽保留上限。

## 验证方法

从仓库根目录运行。需要 JDK 17+ 和可下载 Maven 依赖的网络。Zonky 负责启动真正的 PostgreSQL 服务端，JDBC 执行与实际部署一致的 PostgreSQL 逻辑复制 SQL。

```sh
./mvnw -B -f labs/postgres-logical-replication/pom.xml clean verify
```

| 阶段 | 订阅表的预期结果 |
| --- | --- |
| 完成第一次复制 | 读到订单 101 和 102，状态均为 `NEW`。 |
| 发布端提交一笔包含增、改、删的事务 | 订单 101 状态为 `PAID`，订单 102 删除，订单 103 为 `NEW`。 |
| 暂停后在发布端新增订单 104 | 复制槽仍存在，订阅表暂时没有 104。 |
| 恢复同一个订阅 | 订单 104 出现在订阅表。 |
| 撤掉订阅 | 发布端对应复制槽被移除。 |

实验覆盖单向表复制和未丢失 WAL 时的暂停恢复。表结构变更按版本协调两端执行，应用到报表库时还需验证并行写入、故障转移和目标的可接受延迟。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、JDK 17.0.16 上运行上述 Maven 命令，使用 Zonky Embedded PostgreSQL 2.2.3 启动两个 PostgreSQL 17.12 服务端。1 项集成测试通过，订阅端读到首次复制与全部增删改，并在恢复后从同一复制槽收到订单 104。撤掉订阅后，发布端的复制槽移除。

## 官方资料

- [PostgreSQL 17 逻辑复制快速配置](https://www.postgresql.org/docs/17/logical-replication-quick-setup.html) · [订阅说明](https://www.postgresql.org/docs/17/logical-replication-subscription.html)
- [暂停和恢复订阅](https://www.postgresql.org/docs/17/sql-altersubscription.html) · [逻辑复制监控](https://www.postgresql.org/docs/17/logical-replication-monitoring.html)
- [Zonky Embedded PostgreSQL 的 Java 用法](https://github.com/zonkyio/embedded-postgres#basic-usage)
