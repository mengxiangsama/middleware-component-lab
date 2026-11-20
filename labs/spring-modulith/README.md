# Spring Modulith 订单积分实验

订单和积分是同一个 Spring Boot 应用里的两个模块。订单从 `PENDING` 改成 `PAID` 时发布 `OrderPaid`，Spring Modulith 用同一个 JDBC 数据源在支付事务里把事件及目标监听器登记到 `event_publication`。支付提交后，积分监听器在独立事务里写入 10 积分，成功后 Spring Modulith 填写登记记录的完成时间。

## 运行实验

需要 Java 17+。在仓库根目录使用 Maven Wrapper，集成测试会启动 Spring Boot 和内嵌 H2。

```bash
./mvnw -f labs/spring-modulith/pom.xml clean verify
```

实验沿用仓库的 Spring Boot 3.5.16，搭配 Spring Modulith 1.4.14。测试使用内存 H2 和同步执行器，便于在每次投递结束后查询数据库。应用配置使用文件 H2 保存事件登记和业务表。

| 步骤 | 核对结果 |
| --- | --- |
| 首次支付 | 测试让积分余额更新失败。订单仍是 `PAID`，积分余额和流水都是 0，投递登记尚无完成时间。 |
| 重投原记录 | 调用 `IncompleteEventPublications.resubmitIncompletePublications`，按登记 UUID 重投。原记录有了完成时间，积分余额为 10，流水为 1 条。 |
| 再投递同一笔订单 | 单独提交另一个 `OrderPaid` 事件，生成第二条投递登记。两个登记都已完成，余额仍为 10，流水仍为 1 条。 |
| 回滚支付 | 在支付事务中观察到订单状态和待投递登记，回滚后订单恢复为 `PENDING`，登记也被撤销。 |

查看[集成测试](src/test/java/io/github/modulithlab/OrderPaidDeliveryTest.java)中的重投调用，或在[数据库结构](src/main/resources/schema.sql)里查看流水的联合主键 `(order_id, reward_type)`。

## 清理已完成的投递记录

默认 `UPDATE` 模式在监听器执行成功后填写 `event_publication.completion_date`，记录仍保留在表中。需要留出排查窗口时，可以在现有维护任务中注入 `CompletedEventPublications`，按实际保留期清理。下面以保留最近 7 天完成的记录为例。

```java
completedPublications.deletePublicationsOlderThan(Duration.ofDays(7));
```

在仓库根目录运行单项测试。

```bash
./mvnw -f labs/spring-modulith/pom.xml \
  -Dtest=OrderPaidDeliveryTest#removesOnlyPublicationsCompletedOutsideRetentionWindow test
```

测试把三条记录的发布时间都设为 8 天前，其中一条也在 8 天前完成，一条刚完成，一条投递失败等待重投。清理后只删除 8 天前完成的记录，保留其余两条原记录，积分余额仍为 10，流水仍为 1 条。

- [Spring Modulith 1.4.14 的投递清理 API](https://github.com/spring-projects/spring-modulith/blob/1.4.14/spring-modulith-events/spring-modulith-events-api/src/main/java/org/springframework/modulith/events/CompletedEventPublications.java)

## 积分幂等与适用范围

积分监听器先插入流水，再在同一事务里更新余额。对于同一订单的 `ORDER_PAID` 奖励，只有成功插入流水的投递才会增加余额。Spring Modulith 可重投尚未完成的记录，监听器可能多次执行。积分事务提交后，如果登记完成时间前发生故障，同一个业务键仍能挡住重复入账。

订单与积分在同一服务内时，可以用 Spring Modulith 的事件登记协调模块。订单与积分已经分属独立服务时，沿用仓库的 [RocketMQ 事务消息实验](../../README.md#run-locally)处理跨服务投递。

- [Spring Modulith 1.4 事件文档](https://docs.spring.io/spring-modulith/reference/1.4/events.html)
- [Spring Boot 兼容矩阵](https://docs.spring.io/spring-modulith/reference/1.4/appendix.html#compatibility-matrix)
