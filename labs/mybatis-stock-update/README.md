# MyBatis + H2 用条件更新扣减库存

## 适用场景

一个 SKU 只剩 1 件，两个购买请求都尝试扣减 1 件。将余量检查和扣减交给数据库的一条 `UPDATE`，让调用方通过影响行数判断是否扣减成功。[MyBatis](../../docs/components/database-connectivity.md#mybatis) 将商品编号和数量绑定到 SQL，H2 执行更新与并发控制。

## 接入步骤

1. 引入 `org.mybatis:mybatis:3.5.19` 和目标数据库的 JDBC 驱动。共享 `SqlSessionFactory`，每个请求使用独立的 Session 或事务。实验数据库是 H2 2.3.232，`sku` 设为主键。
2. 在 Mapper 里用 `@Update` 声明 SQL，通过 `#{sku}` 和 `#{quantity}` 绑定参数。扣减量必须大于 0，且余量足以扣减。

```sql
UPDATE stock
SET qty = qty - #{quantity}
WHERE sku = #{sku} AND #{quantity} > 0 AND qty >= #{quantity}
```

3. Mapper 返回整数。返回 `1` 时本次扣减成功。返回 `0` 时更新条件未匹配，调用方可以拒绝这次扣减。写入订单状态与扣库存需要一起成功时，将两者放在同一个数据库事务中，并在提交后确认成功。

## 本地验证

需要 JDK 17+。在仓库根目录运行。实验使用一个本地 H2 内存库，无需外部进程。

```sh
./mvnw -B -f labs/mybatis-stock-update/pom.xml clean verify
```

两个线程分别取得 JDBC 连接和 MyBatis Session，然后同时尝试扣减。断言两个更新的影响行数分别为 1 和 0，再从新 Session 核对余量为 0。另一次负数扣减也返回 0。测试覆盖同一商品的单条更新。跨库扣减、订单幂等和目标数据库的锁超时策略需要独立验证。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、JDK 17.0.16、MyBatis 3.5.19 和 H2 2.3.232 上运行上述命令，1 项集成测试通过。两次并发扣减只有一次成功，最终余量为 0。

## 官方资料

- [MyBatis 3.5.19](https://github.com/mybatis/mybatis-3/releases/tag/mybatis-3.5.19) · [Mapper 注解、SqlSession 与影响行数](https://mybatis.org/mybatis-3/java-api.html)
- [H2 UPDATE](https://h2database.com/html/commands.html#update)
