# MySQL + JDBC 用行锁领取发货任务

## 适用场景

多个 Worker 从同一张发货任务表领取待处理记录。某个 Worker 尚未提交领任务事务时，其他 Worker 可以绕过被锁的行，继续领取当前可用的任务。MySQL InnoDB 持有行锁，Java 用 JDBC 控制每一次领取的事务。

## 接入步骤

1. 将任务存到 InnoDB 表。用主键标识任务，按查询条件建立索引。本例按 `status` 筛选，按 `id` 选一条，因此建 `(status, id)` 索引。
2. 加入依赖 `com.mysql:mysql-connector-j`，从连接池或 JDBC 驱动获取连接。申请任务时开始短事务。本例设置 `READ COMMITTED` 并关闭自动提交。
3. 用下面的锁定读选择一个可领取任务。在同一事务、同一连接把该行改成 `CLAIMED` 并写入 Worker，然后提交。中途出错时回滚。

```sql
SELECT id
FROM shipment_jobs
WHERE status = 'READY'
ORDER BY id
LIMIT 1
FOR UPDATE SKIP LOCKED;
```

返回空表示当前没有可领取的行。锁住的行可能之后回到队列；调度器可隔一段时间再查询。领取事务应短，后续调用外部发货接口时，可用领取记录、超时回收和业务幂等键管理提交后的失败。

## 验证方法

准备 JDK 17+、Docker、OpenSSL 和可下载镜像及 Maven 依赖的网络。从仓库根目录运行。

```sh
sh labs/mysql-skip-locked/verify.sh
```

实验启动一次性 MySQL 8.4.13 容器，使用临时账户和回环端口。A 锁定任务 101 并改为已领取，暂不提交。B 跳过 101，领取并提交 102。A 回滚后，C 再领取并提交 101。最终只有 C/101 和 B/102，没有可领取的任务。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、Docker Engine 27.3.1、MySQL 8.4.13、Connector/J 9.7.0 和 OpenJDK 17.0.16 下运行，1 项集成测试通过。不同连接的领取事务按确定顺序交错，覆盖活动行锁与回滚释放。提交后 Worker 崩溃、吞吐和数据库复制不在这组断言内。

## 官方资料

- [MySQL 8.4 锁定读与多会话示例](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html) · [InnoDB 事务隔离](https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html)
- [MySQL Server 源码](https://github.com/mysql/mysql-server) · [Connector/J 官方仓库](https://github.com/mysql/mysql-connector-j) · [MySQL Docker Official Image](https://github.com/docker-library/mysql)
