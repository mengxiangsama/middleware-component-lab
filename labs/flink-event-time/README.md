# Flink DataStream 事件时间汇总与迟到数据

## 适用场景

订单看板按实际付款时间每 10 秒统计门店收入。消息经过队列或网络后可能乱序。Flink 从事件中提取付款时间，按门店和事件时间窗口聚合。watermark 推进到窗口的最后一个毫秒时触发窗口输出。超过允许迟到范围的记录进入 side output，可供后续核对。

## 接入步骤

1. 从订单事件读取稳定的事件 ID、门店、付款时间戳和整数金额。用 `WatermarkStrategy.withTimestampAssigner` 把付款时间设为事件时间，单位为毫秒。
2. 用 `keyBy` 按门店分组，选择 10 秒事件时间滚动窗口 `TumblingEventTimeWindows`。实验沿用默认的零允许迟到时间。
3. 对主输出汇总金额。用 `sideOutputLateData` 和同一个 `OutputTag` 取出迟到数据。需要大窗口的增量汇总时，可用 `AggregateFunction` 保存累加状态。

实际接入时，可将样本源替换为兼容本地 Flink 版本的 `KafkaSource<Payment>`，在 `env.fromSource(source, strategy, "payments")` 中传入付款时间和 watermark 策略。从消息正文提取付款时间，按业务观测选择 `forBoundedOutOfOrderness` 的最大乱序时长，并按空分区情况设置 `withIdleness`。示例为了稳定复现，在指定样本之后发出固定 watermark。这个生成规则只用于本地数据。

## 本地验证

准备 Java 17，从仓库根目录运行以下命令。Maven 依赖包括 Apache Flink 1.20.3，测试会启动实际的本地 MiniCluster，使用单并行度的有限样本。

```sh
./mvnw -B -f labs/flink-event-time/pom.xml clean verify
```

| 输入顺序 | 付款时间或 watermark（毫秒） | 金额（分） |
| --- | ---: | ---: |
| `p1` | 1000 | 1000 |
| `p2` | 12000 | 400 |
| `p3` | 3000 | 700 |
| watermark | 9999 |  |
| `p4` | 7000 | 500 |
| `p5` | 17000 | 600 |
| watermark | 19999 |  |

`p3` 晚于时间戳更大的 `p2` 到达，但先于关闭第一个窗口的 watermark，仍参与该窗口汇总。`p4` 在第一个窗口关闭后到达，测试通过实际 side output 收集。主输出核对 `[0,10000)` 为 1700 分、`[10000,20000)` 为 1000 分，迟到输出只有 `p4`，金额 500 分。

2026-11-24 在 macOS 26.5 ARM64 和 JDK 17.0.16 上，以 Flink 1.20.3 运行 `clean verify`，1 项测试通过。测试同时断言窗口实际触发时的 watermark 分别为 `9999` 和 `19999`。验证范围为单并行度样本流的事件时间与窗口输出，接入真实数据源和持久化目标时需按所选连接器复核。

## 官方资料

- [Apache Flink 1.20.3 源码](https://github.com/apache/flink/tree/release-1.20.3) · [DataStream 窗口与迟到输出](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/dev/datastream/operators/windows/#getting-late-data-as-a-side-output)
- [事件时间与 watermark](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/dev/datastream/event-time/generating_watermarks/) · [DataStream 示例](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/try-flink/datastream/)
- [Flink 组件手册](../../docs/components/data-pipelines.md#apache-flink)
