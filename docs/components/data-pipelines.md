# CDC、数据同步与流计算

数据库变更捕获（CDC）读取数据库日志中的已提交行变更。托管同步负责在数据源之间复制，流计算处理窗口和有状态的指标，集成管道负责连接多种来源与目标。下文给出组件的常见接入方式和检查项。

| 常见需求 | 组件 | 接入条件 |
| --- | --- | --- |
| 将 MySQL 数据持续复制到 MySQL 副本 | MySQL Replication | 同构实例，可维护一致的初始数据、binlog 保留及复制位点 |
| 将指定表同步到另一 PostgreSQL 数据库 | PostgreSQL Logical Replication | 发布端可开启逻辑 WAL，订阅端已建表，可维护复制槽和结构变更 |
| 从已有 MySQL 表提取变更，接入自有消费者 | Canal | 源库能保留 binlog，团队愿意维护位点、补数和消费端 |
| 捕获不同数据库的变更，由多个独立下游消费 | Debezium | 每种源库都有合适的连接器，需要持久化位点和可重放的下游通道 |
| 数据库搬迁或持续同步，由云服务管理任务 | 阿里云 DTS | 源端、目标端、地域和网络接入受支持，切流方案可核对 |
| 数据库全量加增量已进入 Flink 体系 | Flink CDC | 可维护 Flink 作业状态，所选连接器支持所需 DML 与 DDL |
| 对事件做窗口聚合、关联和有状态计算 | Apache Flink | 有可重放输入，确实需要事件时间或长期状态 |
| 在 Kafka 与外部系统之间运行标准连接器 | Apache Kafka Connect | 已有 Kafka，所需 source 或 sink 插件支持目标版本和数据格式 |
| 注册 Kafka 消息 Schema 并检查版本兼容 | Apicurio Registry | 生产端和消费端使用兼容的序列化格式与 Schema ID 编码 |
| 用统一管道对接多种数据源，做搬运和常见转换 | Apache SeaTunnel | 目标端连接器支持实际主键、更新、删除及恢复语义 |

## SQL 数据同步

以 MySQL `sync_orders(id, status, amount)` 表为例。使用 DTS 时选择表和全量加增量阶段。使用 Flink SQL 时，用 `mysql-cdc` 注册源表，注册支持更新和删除的目标表，通过 `INSERT INTO ... SELECT ...` 提交同步任务。两种方式都要核对表名、字段类型和主键映射，检查目标实际支持的写入模式。 [DTS 同步场景](https://www.alibabacloud.com/help/zh/dts/user-guide/data-synchronization-scenarios) · [Flink CDC MySQL 数据源](https://nightlies.apache.org/flink/flink-cdc-docs-stable/docs/connectors/flink-sources/mysql-cdc/) · [Flink 连接器保证](https://nightlies.apache.org/flink/flink-docs-stable/docs/connectors/table/overview/)

1. 首次全量读取存量行，记录快照对应的增量起始位点。后续 CDC 读取日志中的新增、修改和删除。以目标实际生效的主键应用对应的插入、更新或 upsert、删除。目标若只支持追加，输出是变更记录流，旧版本和删除需通过变更类型表达。
2. 用同一测试范围做一次新增、修改和删除。例如新增 `1001`，更新已有记录 `1002` 的 `status`，删除已有记录 `1003`。观察任务追过这批提交的水位，避免在核对期间继续修改这段样本。
3. 在两端执行相同范围的查询（表名经过映射时替换为对应目标表）。除行数外，对照每个键、更新字段和删除结果。此例应看到 `1001`、更新后的 `1002`，以及 `1003` 已不存在。同样的总数可能掩盖一增一漏，也可能掩盖旧值。

```sql
SELECT COUNT(*) AS row_count
FROM sync_orders
WHERE id BETWEEN 1001 AND 1003;

SELECT id, status, amount
FROM sync_orders
WHERE id BETWEEN 1001 AND 1003
ORDER BY id;
```

数据同步推进表中记录。若还要同步源端 DDL，核对链路对加列、精度变更、重命名和删列的处理。部署环境里的 SQL 变更脚本可用 Flyway 或 Liquibase 做版本管理。 [数据库变更工具](release-tools.md#flyway)

## MySQL Replication

- MySQL 原生复制通过源实例的 binlog 向 MySQL 副本传输变更。可以用 binlog 文件和位置，也可以用 GTID 自动定位。默认异步，源端提交时副本可能尚未接收或应用，读副本可能看到旧值。 [MySQL 复制概述](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/replication.html) · [GTID 自动定位](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/replication-gtids-auto-positioning.html)
- **常用用法**。以带存量数据的 GTID 复制为例，确认源和副本的 `server_id` 唯一，源启用 binlog，按版本启用 GTID 并准备复制账户。在副本恢复与 GTID 集合匹配的一致性备份。通过 `CHANGE REPLICATION SOURCE TO` 设置来源和 `SOURCE_AUTO_POSITION = 1`，再执行 `START REPLICA`。采用文件位点时，从一致性快照记录的文件和位置开始。 [GTID 初始化与备份](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/replication-gtids-failover.html) · [开始 GTID 复制](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/replication-gtids-howto.html)
- 在副本执行 `SHOW REPLICA STATUS`，查看 `Replica_IO_Running`、`Replica_SQL_Running`、`Last_IO_Error` 和 `Last_SQL_Error`。GTID 模式可以在测试变更提交后记录源端 `@@GLOBAL.gtid_executed`，在副本带超时调用 `WAIT_FOR_EXECUTED_GTID_SET`，返回 `0` 后用上述 SQL 核对记录。`Seconds_Behind_Source` 为 `0` 也可能存在接收端落后。再暂停并恢复测试副本，验证从原位点追平。源日志已清理时，按手册重新准备一致性基线。 [复制状态](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/show-replica-status.html) · [GTID 等待函数](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/gtid-functions.html) · [GTID 恢复边界](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/replication-gtids-auto-positioning.html)

## PostgreSQL Logical Replication

- **常用用法**。发布库用 publication 选择复制的表，订阅库用 subscription 接收数据。发布端设置 `wal_level=logical` 并预留 WAL sender 和复制槽，订阅端预留逻辑复制 worker。复制账号需要连接和复制权限，首次复制需要源表的 `SELECT` 权限。目标库先建同名且兼容的表。默认复制初始存量，再追增量。下面的连接值按测试环境填写，认证信息从所在环境安全提供。 [快速设置](https://www.postgresql.org/docs/current/logical-replication-quick-setup.html) · [复制参数](https://www.postgresql.org/docs/current/logical-replication-config.html) · [账号权限](https://www.postgresql.org/docs/current/logical-replication-security.html)

```sql
-- 两端均执行
CREATE TABLE public.repl_orders (id bigint PRIMARY KEY, status text NOT NULL);

-- 发布库执行，保留一条存量数据验证初始复制
INSERT INTO public.repl_orders VALUES (1001, 'pending');
CREATE PUBLICATION lab_orders_pub FOR TABLE public.repl_orders;

-- 订阅库执行，将连接参数替换为测试发布库
CREATE SUBSCRIPTION lab_orders_sub
  CONNECTION 'host=publisher.example.com dbname=lab user=replicator'
  PUBLICATION lab_orders_pub;
```

- **复制边界**。发布 `UPDATE` 和 `DELETE` 时，通常用主键作为 replica identity。无主键时可指定满足条件的唯一索引，或在验证定位成本后用 `REPLICA IDENTITY FULL`。DDL 和序列值需分别处理。增列时可先更新订阅端再更新发布端，切到目标端写入前检查序列位置。消费者停滞时复制槽会保留 WAL，需关注源库磁盘和 `pg_replication_slots.restart_lsn`。若达到 `max_slot_wal_keep_size` 后所需 WAL 已被清理，应核对水位并重新建立一致性基线。 [发布与 replica identity](https://www.postgresql.org/docs/current/logical-replication-publication.html) · [复制限制](https://www.postgresql.org/docs/current/logical-replication-restrictions.html) · [WAL 保留参数](https://www.postgresql.org/docs/current/runtime-config-replication.html)
- **验证**。先在订阅端读到 `1001`，然后在发布端新增 `1002`、更新 `1001`，确认目标追平后再删除 `1002`。用同一主键范围核对结果。暂停订阅后再提交一条变更，观察发布端 slot 保留和订阅端 `pg_stat_subscription`，恢复后检查变更追平。 [监控](https://www.postgresql.org/docs/current/logical-replication-monitoring.html) · [订阅管理](https://www.postgresql.org/docs/current/sql-altersubscription.html)

[运行 PostgreSQL 订单表逻辑复制实验](../../labs/postgres-logical-replication/README.md)。两实例通过真实复制协议传输数据，测试读取订阅端的具体行，并核对暂停、恢复及撤掉订阅前后的复制槽。

## Canal

- **常用用法**。启用 MySQL binlog 并核查所选版本要求的日志格式、权限和保留期。创建订阅实例并限定库表。直连客户端执行 `subscribe`、`getWithoutAck`，读取变更类型和行数据，目标落地后 `ack`，处理失败时 `rollback`。对测试表做一次新增、修改和删除，核对三类事件。 [Canal Client API](https://github.com/alibaba/canal/wiki/ClientAPI)
- 适合自管 MySQL binlog 订阅以及已有 Canal 消费方的系统。需要历史数据时，准备与起始位点对齐的全量基线，可使用独立回填或所选适配器的导入能力。核查主从切换之后仍能解释当前文件位置或 GTID。 [Canal 工作原理](https://github.com/alibaba/canal/wiki/Introduction)
- 直连客户端在目标变更持久化后按顺序确认批次。目标已提交但确认未成功时可能重读，按来源事件标识或业务版本消除重放影响。并行处理时，按实体维持顺序，批次确认也不能越过未完成的批次。 [Canal Client API](https://github.com/alibaba/canal/wiki/ClientAPI)
- 在目标提交之后、批次确认之前中断客户端。恢复后核对目标内容，再演练源日志过期后的重建路径。

## 阿里云 DTS

- 此处的 DTS 指阿里云 Data Transmission Service。搬库选择迁移，长期复制选择同步，向应用提供增量变更选择订阅。评估具体链路支持的数据源、地域、网络接入、权限、主键限制、数据类型映射、DDL 和服务成本。 [DTS 产品概述](https://www.alibabacloud.com/help/zh/dts/product-overview/what-is-dts)
- **常用用法**。以单向同步为例，在控制台创建任务，填写源库和目标库，选择库表和需要的结构、全量、增量阶段，预检查通过后启动。等待增量追平，在源表做一次增删改，再对照目标表、任务延迟和校验结果。 [DTS 同步场景](https://www.alibabacloud.com/help/zh/dts/user-guide/data-synchronization-scenarios)
- 同步可按链路选择结构、全量和增量阶段。已有目标基线时，先把基线与源端增量位点对齐。双向同步另需确认写入归属、冲突处理和重置行为。迁移验收定义停止旧端写入的时机、追平水位及源目标对账条件。 [DTS 同步场景](https://www.alibabacloud.com/help/zh/dts/user-guide/data-synchronization-scenarios)
- 用带写入负载的测试库演练全量转增量、任务暂停追平和不兼容 DDL。按同一水位比较行数、关键字段、删除与业务汇总，记录切换耗时以及如何恢复旧端写入。

## Apache Flink

- 适合事件时间窗口、跨流关联和有状态计算。定义业务键、水位线、迟到数据处理、状态保留、热点键和背压处置。简单的逐行映射可先评估集成管道，以减少集群和状态维护成本。 [Flink 有状态流处理](https://nightlies.apache.org/flink/flink-docs-stable/docs/concepts/stateful-stream-processing/)
- **常用用法**。在 SQL Client 中用 `CREATE TABLE` 注册源和 sink，按需要设置事件时间与 `WATERMARK`，以 `INSERT INTO ... SELECT ...` 提交计算。先用时间和结果已知的样本验证窗口，再投递一条乱序样本观察输出。Java 作业也可以用 DataStream API。 [Flink SQL 快速入门](https://nightlies.apache.org/flink/flink-docs-stable/docs/getting-started/quickstart-sql/)
- 作业从持久化 checkpoint 恢复状态并重放源数据。单个目标的端到端保证依赖可回放的源、能完成并恢复提交的事务 sink，或具备严格幂等语义的写入。以主键覆盖相同记录只能保护满足该条件的结果，读者仍可能在重放时观察到旧值。逐个核实连接器、写入模式和版本。 [Flink 连接器容错保证](https://nightlies.apache.org/flink/flink-docs-stable/docs/connectors/table/overview/)
- 在 checkpoint 前后分别终止作业，核对窗口、迟到事件和目标输出。用真实连接器测试待提交事务恢复，检查目标暂不可用时是否可以在日志保留期内追平。

- **事件时间窗口**。在 [订单付款样本实验](../../labs/flink-event-time/README.md) 中，用付款时间和门店构建 10 秒滚动窗口。观察乱序但仍能入窗的事件，再推进 watermark，让已经超过迟到边界的事件进入 side output。测试分别核对两个窗口的金额和迟到记录。

## Flink CDC

- 适合在 Flink 作业或 CDC Pipeline 中读取库表快照及后续行变更。以 MySQL 增量快照为例，按分片做快照并记录进度，随后衔接 binlog。评估分片列、源库读负载、连接器与 Flink 兼容性，以及日志能否覆盖回填和恢复窗口。 [Flink CDC MySQL 数据源](https://nightlies.apache.org/flink/flink-cdc-docs-stable/docs/connectors/flink-sources/mysql-cdc/)
- **常用用法**。在 Pipeline YAML 中指定 `source`、`sink` 和选用的启动方式，准备源表与目标连接器，启用 checkpoint 后提交作业。用源端已有样本验证快照，再新增、修改、删除一行，核对目标收敛。使用 Flink SQL 时可注册 `mysql-cdc` 表观察变更结果。 [Flink CDC Pipeline 快速入门](https://nightlies.apache.org/flink/flink-cdc-docs-stable/docs/get-started/quickstart-for-1.20/cdc-up-quickstart-guide/)
- Pipeline 对源 DDL 的处理取决于 schema 演进策略和 sink 能力。先核查加列、改类型、重命名、删列等操作的支持、失败处理和数据转换。源端 checkpoint 恢复语义和目标端落地语义需分别验证。 [Flink CDC Schema 演进](https://nightlies.apache.org/flink/flink-cdc-docs-stable/docs/core-concept/schema-evolution/)
- 在快照分片读到一半时更新和删除同一主键，并在全量转增量附近重启，检查旧快照不会覆盖新值或使删除的行重新出现。再通过实际连接器验证一次允许的 DDL 和一次不支持的 DDL。

## Apache Kafka Connect

- Connect worker 运行并分配连接器的任务。Source connector 把外部数据写入 Kafka，sink connector 读 Kafka 并写外部目标。Debezium 的 MySQL 连接器是运行在 Connect 上的 source 插件，读取 binlog 并产出变更记录。学习时可用单 worker，多个 worker 的 distributed 模式通过 Kafka 内部主题持久化配置、源位点和任务状态，并支持任务重新分配。 [Kafka Connect 用户指南](https://kafka.apache.org/44/kafka-connect/user-guide/) · [Debezium MySQL 连接器](https://debezium.io/documentation/reference/stable/connectors/mysql.html)
- **常用用法**。给 worker 安装兼容插件，用 `GET /connector-plugins` 检查所在 worker 的插件。配置 `connector.class`、转换器以及插件所需的源或目标参数，sink 指定 `topics` 或 `topics.regex`。用包含 `name` 和 `config` 的 JSON 调用 `POST /connectors`，再通过 `GET /connectors/{name}/status` 同时检查 connector 和各 task 的状态。单 worker 可用 FileStream source 和 sink 把一行文本经测试 topic 送到目标文件。 [Kafka Connect REST API 与示例](https://kafka.apache.org/44/kafka-connect/user-guide/)
- 支持位点管理接口的版本可用 `GET /connectors/{name}/offsets` 查看位点。Source 位点由插件定义，sink 使用 Kafka 消费位点。调整或重置位点前先停任务，并核对回放范围和外部目标已有数据。按错误阶段配置重试和容忍策略，在支持的 sink 错误路径配置 DLQ。跳过坏记录会使主目标缺少该记录，需核对 DLQ 及处理结果。Exactly-once 依赖连接器和 worker 配置，source 需要支持此机制的 distributed worker，sink 还需验证外部写入与恢复。 [Kafka Connect 位点、错误及交付语义](https://kafka.apache.org/44/kafka-connect/user-guide/)
- 写入可识别的记录后重启 worker，记录任务迁移与恢复前后的位点和目标内容。再投递一条无法转换的测试记录，观察任务停止或记录进入 DLQ 是否符合配置。在 sink 已落地、消费位点尚未提交时中断任务，核对恢复后是否产生额外写入。

## Apicurio Registry

- **常用用法**。集中登记 Avro、JSON Schema 或 Protobuf 等消息 Schema。用 `groupId` 归类、用 `artifactId` 标识 Schema，按 version 管理演进。每个版本有独立的 `globalId`，内容相同的多个版本可共享 `contentId`。在控制台创建一项 Avro artifact 并上传首版，例如 `lab/orders-value` 的版本 `1`，Schema 可使用含 `id: long` 和 `status: string` 的 `OrderStatus` record。 [核心概念](https://www.apicur.io/registry/docs/apicurio-registry/3.3.x/getting-started/assembly-registry-concepts-glossary.html) · [控制台注册](https://www.apicur.io/registry/docs/apicurio-registry/3.3.x/getting-started/assembly-managing-registry-artifacts-ui.html)
- **Kafka 接入**。以 3.3 系列的 Avro Java SerDes 为例，生产端配置 `AvroKafkaSerializer`，消费端配置 `AvroKafkaDeserializer`，两端指向同一 Registry API（`apicurio.registry.url`，例如 `https://registry.example.com/apis/registry/v3`）。生产端通过 `apicurio.registry.artifact.group-id`、`apicurio.registry.artifact.artifact-id`、`apicurio.registry.artifact.version` 选择预注册版本。序列化器把 Schema ID 写入消息载荷前缀，或按配置写入 header。反序列化器据该 ID 查到写入 Schema。两端需匹配 ID 类型、存放位置和编码长度。3.3 默认用 4 字节 `contentId`，升级旧客户端前验证历史消息回放。 [Kafka SerDes 配置](https://www.apicur.io/registry/docs/apicurio-registry/3.3.x/getting-started/assembly-configuring-kafka-client-serdes.html) · [2.x 到 3.x 的 ID 编码变更](https://www.apicur.io/registry/docs/apicurio-registry/3.1.x/getting-started/assembly-migrating-registry-v2-v3.html)
- **兼容规则和验证**。在 artifact 上启用 `COMPATIBILITY`，例如 `BACKWARD_TRANSITIVE` 要求新 Schema 能读取所有旧版本写入的数据。相同规则按 artifact、group、全局的顺序生效。在版本 2 加一个默认值为 `null` 的 Avro 可选字段，验证能注册且新消费者能回放旧消息。再尝试把 `status` 从字符串改为整数，检查上传被拒绝且原版本仍可读取。用未缓存 Schema 的消费者模拟 Registry 不可用，核对查找失败、重试与恢复。 [规则与优先级](https://www.apicur.io/registry/docs/apicurio-registry/3.3.x/getting-started/assembly-intro-to-registry-rules.html) · [兼容模式](https://www.apicur.io/registry/docs/apicurio-registry/3.3.x/getting-started/assembly-rule-reference.html)

## Debezium

- 适合将数据库行级变更分发给多个下游。以 MySQL 连接器经 Kafka Connect 写 Kafka 为例，初始快照建立基线，随后从对应 binlog 位点读取增量。保留 Connect 位点、MySQL 连接器的 schema history 和源日志，分开规划 Kafka 主题的保留期。部署与维护范围还包括连接器权限和消费者的 schema 兼容。 [Debezium MySQL 连接器](https://debezium.io/documentation/reference/stable/connectors/mysql.html)
- **常用用法**。通过 Kafka Connect 注册 MySQL 连接器，选择监听表、主题前缀和快照模式。向测试表写入数据，读取主题中的键及 `before`、`after`、`op`、`source` 字段。核对快照记录、创建、更新、删除和后续适用的 tombstone。 [Debezium MySQL 连接器](https://debezium.io/documentation/reference/stable/connectors/mysql.html)
- 恢复时已发出但未记入位点的记录可能重复。以消息键和可追踪位点核对重发记录。表主题的消息分区会影响观察到的顺序，跨分区的汇总顺序应以产品实际写入和消费行为为准。 [Debezium 故障恢复](https://debezium.io/documentation/reference/stable/connectors/mysql.html#mysql-when-things-go-wrong)
- 分别在快照和增量阶段终止连接任务，检查重发对目标的影响。模拟过期位点或缺失 schema history，验证告警、恢复备份和受控重做快照流程。

在 Java 服务中直接消费订单事件时，可使用 [Debezium Engine + PostgreSQL 实验](../../labs/debezium-postgres-events/README.md)。测试经真实 `pgoutput` 读取初始快照和三类变更，并检查事件的 `op`、主键、`before` 与 `after`。

## Apache SeaTunnel

- 适合通过 Source、Transform、Sink 组合完成异构迁移、批量装载或 CDC 同步。优先确认具体连接器能否处理全量与增量衔接、类型映射、主键 upsert、删除和 DDL，并核查引擎与连接器版本的兼容性。 [SeaTunnel CDC 管道](https://seatunnel.apache.org/docs/architecture/cdc-pipeline-architecture/)
- **常用用法**。用 `env` 设置执行模式，配置 `source` 和 `sink`，需要字段转换时加 `transform`。可先跑 `FakeSource` 到 `Console` 观察字段映射，再换成目标连接器，用固定样本核对导入、更新和删除。 [SeaTunnel 入门](https://seatunnel.apache.org/docs/getting-started/overview/) · [作业配置](https://seatunnel.apache.org/docs/getting-started/job-configuration-guide/)
- 某个 sink 是否能在恢复时避免重复影响，取决于该连接器的实现、启用的提交模式和外部目标能力。验证 checkpoint 后能否从可回放源续读，以及事务提交或按键幂等能否处理重放。多个 sink 各自恢复成功，仍需分别做结果核对。 [SeaTunnel 连接器能力](https://seatunnel.apache.org/docs/introduction/concepts/connector-v2-features/)
- 在写入目标后中断任务，核对恢复后的行数、最后版本和删除结果。做一次目标不可用后追平，并验证来自源端的新字段如何进入目标。

## 运行检查

记录测试使用的版本、源和目标连接器、启动模式、恢复位点及日志保留期。对全量加增量任务，用相同主键做插入、更新和删除，按同一同步水位核对源端与目标端。对支持恢复的任务，记录重启前后的位点、重复记录和目标最终状态。需要验证 exactly-once 时，指定一个目标和写入模式，在写入与位点确认之间中断任务，比较重放前后的最终结果。
