# PostgreSQL + Debezium 事务 Outbox

发货服务保存订单状态后，需要让履约看板收到事件。应用在同一个 PostgreSQL 事务里写业务状态和待投递事件。Debezium 从 Outbox 表读取已提交记录，把它们转成带订单键和事件 ID 的消息供下游消费。

## 接入步骤

1. 业务表 `shipment_order` 保存订单状态，`shipment_outbox` 保存应用准备发送的事件。两次写入用同一个数据库连接和事务。表结构和事务写入见 [Java 集成测试](src/test/java/io/github/middlewarelab/debezium/ShipmentOutboxTest.java)。[PostgreSQL 事务](https://www.postgresql.org/docs/17/tutorial-transactions.html)

   ```sql
   BEGIN;
   INSERT INTO public.shipment_order (order_id, status) VALUES (102, 'PACKED');
   INSERT INTO public.shipment_outbox (id, aggregatetype, aggregateid, type, payload)
   VALUES ('evt-commit', 'shipment', '102', 'PACKED', '{"orderId":102,"status":"PACKED"}'::jsonb);
   COMMIT;
   ```

2. PostgreSQL 启用 `wal_level=logical`。Publication 和 `table.include.list` 只选 `public.shipment_outbox`。给 Debezium PostgreSQL Connector 配置 `pgoutput`、Publication 和复制槽，再用 `DebeziumEngine` 的 Connect 格式把记录交给 Java 回调。 [PostgreSQL Connector](https://debezium.io/documentation/reference/3.2/connectors/postgresql.html) · [Debezium Engine](https://debezium.io/documentation/reference/3.2/development/engine.html)
3. 启用 Outbox Event Router。它用 `aggregatetype` 生成主题名，用 `aggregateid` 作为消息键，传递 `payload`，并把 `id` 放到消息头。示例额外将 `type` 映射到 `eventType` 消息头。 [Outbox Event Router](https://debezium.io/documentation/reference/3.2/transformations/outbox-event-router.html)

   ```properties
   transforms=outbox
   transforms.outbox.type=io.debezium.transforms.outbox.EventRouter
   transforms.outbox.table.fields.additional.placement=type:header:eventType
   ```

## 运行与验证

使用 JDK 17+，以普通用户从仓库根目录运行。Maven 会准备嵌入式 PostgreSQL 所需的本机二进制和 Debezium 依赖。

```sh
./mvnw -B -f labs/debezium-postgres-outbox/pom.xml clean verify
```

先通过一条快照事件确认回调已工作，再用独立的 JDBC 连接在事务结束前查询两张表，确认尚未提交的订单和 Outbox 行都不可见。事务结束后，验证下列结果。

| 动作 | 业务表和 Outbox 行数 | Debezium Engine 回调 |
| --- | --- | --- |
| 回滚订单 `101` 与 `evt-rollback` | `0 / 0` | 有序流中未出现 `evt-rollback` |
| 提交订单 `102` 与 `evt-commit` | `1 / 1` | `topic=outbox.event.shipment`、`key=102`、消息头 `id=evt-commit`、`eventType=PACKED`，JSON 含订单号和状态 |
| 提交后续订单 `103` 与 `evt-barrier` | `1 / 1` | 下一条为 `evt-barrier`，证明消费已前进到回滚之后的提交 |

测试等待回调依次收到两条提交消息，用后续提交确认消费进度。2026-11-27 在 macOS 26.5 ARM64 和 OpenJDK 17.0.16 上运行 PostgreSQL 17.12、Debezium 3.2.7.Final，集成测试通过，实际主题、订单键、消息头和 JSON 均与上表一致。

验证范围是 PostgreSQL 事务、逻辑复制、Router 转换与进程内 `SourceRecord` 回调。下游可用 `id` 做幂等去重，接入 Kafka 和外部目标时还需验证投递重试与恢复。

- [Debezium 3.2.7.Final 源码](https://github.com/debezium/debezium/tree/v3.2.7.Final) · [Debezium 官方示例](https://github.com/debezium/debezium-examples)
