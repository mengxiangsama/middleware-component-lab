# Spring Batch + H2 从上次提交的批次继续导入

## 适用场景

日结任务把发货对账 CSV 导入数据库。文件较大时，每处理一批就提交，后续失败后从最近的提交位置续跑。Spring Batch 驱动读取、写入和分批提交，文件模式 H2 保存导入结果与 Spring Batch 的 JDBC 作业元数据。

## 接入步骤

1. 在 Maven 中加入 `org.springframework.batch:spring-batch-core` 和 `com.h2database:h2`。本例用文件型 `jdbc:h2:file:...` 数据源，首次加载框架提供的 `schema-h2.sql`，并建立写入表 `imported_shipment`。续跑时连接原数据库。
2. 创建 JDBC `JobRepository` 和事务管理器。让作业仓库与 `JdbcBatchItemWriter` 使用同一个数据源，并将同一个 JDBC 事务管理器交给 `StepBuilder.chunk(2, transactionManager)`。在一个 chunk 中完成业务写入和读取位置的提交。
3. 配置启用状态保存的 `FlatFileItemReader`。保持 reader 名称、作业和步骤名称、输入文件内容及顺序稳定。通过 `JobLauncher` 传入标识参数 `importId`，将其和作业名称一起作为该次导入的身份。
4. 失败后用相同的 `importId` 和输入再次启动。Spring Batch 创建新的 `JobExecution`，从同一 `JobInstance` 已保存的 `ExecutionContext` 恢复读取位置。可用 `JobExplorer` 查询已持久化的步骤状态和检查点。

[CSV 样例](src/test/resources/shipments.csv)的记录经 reader 映射成发货编号和整数分，再由 writer 插入业务表。业务表的主键会使重复插入同一条发货记录失败。

## 验证方法

需要 JDK 17+ 和可下载 Maven 依赖的网络。在仓库根目录运行。

```sh
./mvnw -B -f labs/spring-batch-chunk-restart/pom.xml clean verify
```

| 阶段 | 实验操作和预期结果 |
| --- | --- |
| 第一次运行 | 第一批 `101, 102` 提交。第二批 `103, 104` 交给 JDBC writer 后故意抛出异常。作业为 `FAILED`，H2 中只留下前两条。 |
| 重新打开文件库 | 用新建的框架对象读取上一执行的元数据，确认 `shipmentReader.read.count=2`，步骤的写入数为 2。 |
| 再次启动 | 相同 `importId` 对应同一作业实例和新的执行记录。只读取并写入 `103, 104, 105`，状态变为 `COMPLETED`，业务表共有 5 条且金额与 CSV 一致。 |

## 实测记录

2026-11-25 在 macOS 26.5 ARM64、OpenJDK 17.0.16、Spring Batch 5.2.6 和 H2 2.3.232 上执行，1 项集成测试通过。覆盖单进程、文件库、受控失败及新建作业运行对象后的续跑。遇到进程强制退出或输出包含远程接口调用时，需另外演练作业状态恢复并设计外部写入的幂等处理。

## 官方资料

- [Spring Batch 5.2.6](https://github.com/spring-projects/spring-batch/releases/tag/v5.2.6) · [失败与重启示例](https://github.com/spring-projects/spring-batch/blob/v5.2.6/spring-batch-samples/README.md#fail-restart-sample) · [JDBC JobRepository](https://docs.spring.io/spring-batch/reference/5.2/job/configuring-repository.html)
- [Chunk 提交](https://docs.spring.io/spring-batch/reference/5.2/step/chunk-oriented-processing.html) · [ItemStream 状态保存](https://docs.spring.io/spring-batch/reference/5.2/readers-and-writers/item-stream.html) · [H2 数据库 URL](https://h2database.com/html/features.html#database_url)
