# SQLite + JDBC 在写入时读一致的报表

## 适用场景

单机门店应用把发货记录写入本地 SQLite 文件。报表要执行多条查询并使用同一批已提交的数据，后台仍需登记新的发货。SQLite 的 WAL 允许写事务在读事务期间提交。Java 通过 Xerial SQLite JDBC 为报表和写入分别打开连接。

## 接入步骤

1. 在 Java 工程加入 Maven 依赖 `org.xerial:sqlite-jdbc`。本实验固定为 `3.53.4.0`，底层 SQLite 版本可以用 `SELECT sqlite_version()` 核对。
2. 将数据库放在本机文件系统上。初始化时运行 `PRAGMA journal_mode = WAL` 并检查返回值是 `wal`。多个 JDBC 连接使用同一文件地址 `jdbc:sqlite:/path/to/shipments.db`。
3. 报表连接执行 `BEGIN`，在同一连接上完成所需的 `SELECT`。首次读取固定该事务的快照，结束读事务后才能在下一次查询看到之后提交的发货。
4. 写入连接先执行 `BEGIN IMMEDIATE` 取得写事务，再写数据并及时提交。失败时回滚。SQLite 每次只允许一个写事务。可以按业务延迟配置有限的锁等待，处理 `SQLITE_BUSY`；遇到 `SQLITE_BUSY_SNAPSHOT` 时结束当前读事务，重新读取后再执行完整事务。

实验显式执行 SQL 的 `BEGIN`、`COMMIT` 和 `ROLLBACK`，使事务边界可见。在事务由框架管理的应用里，由同一事务管理层负责连接与提交。长时间读事务可能阻碍 checkpoint 推进，应用需控制报表事务时长并观察 WAL 大小。

## 验证方法

准备 JDK 17+，允许 Maven 下载依赖，在仓库根目录运行。

```sh
./mvnw -B -f labs/sqlite-wal-snapshot/pom.xml clean verify
```

测试用三个连接访问同一个临时数据库，以确定的顺序交错读取与写入。报表先读到 1200 分。订单 102 未提交时，另一个连接也只读到 1200 分，申请写事务返回 `SQLITE_BUSY`。订单 102 提交后，另一个连接读到 2000 分，已打开的报表仍是 1200 分。尝试用旧快照写入返回 `SQLITE_BUSY_SNAPSHOT`。结束该事务后，同一连接读到 2000 分。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、OpenJDK 17.0.16、Xerial 驱动 3.53.4.0 和 SQLite 3.53.4 下运行，1 项集成测试通过。验证范围为单机文件库的事务隔离和写入竞争。业务负载下的查询吞吐、锁等待时间和故障恢复需在使用场景中另行测量。

## 官方资料

- [Xerial SQLite JDBC 3.53.4.0](https://github.com/xerial/sqlite-jdbc/releases/tag/3.53.4.0) · [JDBC 使用示例](https://github.com/xerial/sqlite-jdbc/tree/3.53.4.0#usage)
- [SQLite WAL](https://www.sqlite.org/wal.html) · [事务隔离与快照](https://www.sqlite.org/isolation.html) · [SQLite 上游 WAL 测试](https://github.com/sqlite/sqlite/blob/version-3.53.4/test/wal.test)
