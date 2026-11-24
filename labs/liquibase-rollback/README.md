# Liquibase + H2 按版本标签回退订单表

## 适用场景

订单服务的旧版本仍在使用 `orders.id` 和 `orders.amount_cents`，新版本准备新增 `delivery_status`。发布前先演练失败时怎样撤销新增列，并确认旧服务依赖的订单行和建表记录仍然可用。

| 组件 | 分工 |
| --- | --- |
| [Liquibase](../../docs/components/release-tools.md#liquibase) | 按 XML changelog 执行变更，在 `DATABASECHANGELOG` 标记旧版本，预览并执行回退 SQL。 |
| H2 | 提供真实的 SQL 表、订单行和迁移记录。通过 JDBC 查询列结构、订单数据和已应用的变更。 |

## 接入步骤

1. 在 [orders.xml](src/test/resources/orders.xml) 先建立订单表，再单独放一个 `tagDatabase` changeset，标记为 `orders-v1`。后续变更新增 changeset，并保留已经发布的 ID、作者及文件路径。
2. 对演练数据库执行 `update-to-tag`，将结构部署到 `orders-v1` 并写入一笔旧订单。再执行 `update`，验证新增的 `delivery_status` 能读到默认值 `PENDING`，随后写入 `SHIPPED` 模拟新版本业务。
3. 执行指向 `orders-v1` 的 `rollback-sql`，先核对其中的 `DROP COLUMN DELIVERY_STATUS`，再执行 `rollback`。这个 XML `addColumn` 变更有自动生成的回退操作。
4. 用 JDBC 查询 `DATABASECHANGELOG` 和 `orders`。确认旧订单仍可读取，再次 `update` 时，Liquibase 重建标签并重新应用新增列。新建列按默认值初始化，先前列中的 `SHIPPED` 随删列操作消失。

## 本地验证

需要 JDK 17 和仓库的 Maven Wrapper。在仓库根目录运行以下命令。实验使用独立的内存 H2 数据库，退出即清理。

```sh
./mvnw -B -f labs/liquibase-rollback/pom.xml clean verify
```

| 阶段 | 检查内容 |
| --- | --- |
| `update-to-tag orders-v1` | 已应用建表与标签两个 changeset，业务列 `delivery_status` 尚未加入。 |
| `update` | 新列默认值为 `PENDING`，随后可写入 `SHIPPED`。 |
| `rollback-sql` | 输出删列 SQL，订单状态和迁移记录仍为预览前的值。 |
| `rollback orders-v1` | `delivery_status` 删除，表中的订单 42 仍是 1200 分。`DATABASECHANGELOG` 只保留建表记录，`tagDatabase` 自己的记录也随回退删除。 |
| 重新 `update` 两次 | 首次重建标签并增加列，订单 42 的状态恢复为默认值 `PENDING`。再次更新不重复登记 changeset，订单仍只有一行。 |

macOS 26.5 ARM64、JDK 17.0.16、Liquibase 4.33.0 和 H2 2.3.232 下，上述集成测试通过。接入实际项目时，先在目标数据库版本演练 DDL 的锁、事务及数据恢复。回退期间需要保留新列中的数据时，先导出该列或设计前向修复。

## 上游资料

- [Liquibase 4.33.0 发布记录](https://github.com/liquibase/liquibase/releases/tag/v4.33.0) · [标签迁移命令实现](https://github.com/liquibase/liquibase/blob/v4.33.0/liquibase-standard/src/main/java/liquibase/command/core/UpdateToTagCommandStep.java)
- [update-to-tag](https://docs.liquibase.com/oss/reference-guide-4-33/init-update-and-rollback-commands/update-to-tag) · [tagDatabase](https://docs.liquibase.com/oss/reference-guide-4-33/change-types/tagdatabase) · [rollback](https://docs.liquibase.com/oss/reference-guide-4-33/init-update-and-rollback-commands/rollback)
- [H2 的内存数据库连接](https://h2database.com/html/features.html#in_memory_databases)
