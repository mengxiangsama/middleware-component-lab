# Kafka Connect + Kafka 传输发货文件并按位点续跑

## 适用场景

发货程序持续向本地文件追加事件，下游对账程序读取另一份输出文件。想让两个程序经 Kafka 传递新增记录，可以用 Kafka Connect 运行 source 和 sink。Source 读入每一行并写到 Kafka Topic，Kafka 保存消息，Sink 从 Topic 消费并追加到目标文件。Worker 重启时，两端各自恢复已提交的位点。

## 接入步骤

1. 准备 Kafka，并创建一个持久化测试 Topic。本例的 `shipment-events` 只有一个分区。
2. 用 Kafka 官方发行包运行 standalone Worker。让 `plugin.path` 指向同版本的 `libs/connect-file-4.4.0.jar`，把 `key.converter` 和 `value.converter` 设为 `StringConverter`，并用 `offset.storage.file.filename` 指定 Source 位点文件。
3. 配置 `FileStreamSourceConnector`。用 `file` 指定输入路径，`topic=shipment-events` 指定目标 Topic。配置 `FileStreamSinkConnector`。用 `topics=shipment-events` 指定来源，`file` 指定输出路径。两端各运行一个任务。
4. 用 `GET /connectors/{name}/offsets` 检查 Source 的文件路径和读取位置，以及 Sink 的 Topic、分区与下一条消费位点。重启 standalone Worker 时，传入相同的三个配置文件，并继续使用原来的连接器名称、数据文件、Topic 和 Source 位点文件。

## 验证方法

准备 Java 17+、Python 3 和 Apache Kafka 4.4.0 的二进制发行包。从官方页面下载 `kafka_2.13-4.4.0.tgz`，按 SHA-512 文件核对后解压。在仓库根目录运行。

```sh
python3 labs/kafka-connect-filestream/verify.py /path/to/kafka_2.13-4.4.0
```

脚本创建临时目录，并在回环地址启动真实的单节点 KRaft Kafka 和 standalone Worker。第一次把两条 ASCII 发货记录写到输出文件，等 Source 和 Sink 都提交位点后正常停止 Worker。Kafka 保持运行。脚本停机期间追加两条记录，再重新启动 Worker。最终输出刚好包含原来的两条和新增的两条，并且 Source 位置和 Sink 的下一条位点一起前进。

## 实测记录

2026-11-27 在 macOS 26.5 ARM64、OpenJDK 17.0.16 和 Python 3.13.7 上使用官方 Kafka 4.4.0 二进制包运行。Kafka Broker、Connect Worker 和 FileStream 插件均为 4.4.0。

| 阶段 | 输出文件 | Source 读取位置 | Sink 下一条位点 |
| --- | --- | --- | --- |
| 停止 Worker 前 | `shipment-101`、`shipment-102` | 42 | 2 |
| Worker 续跑后 | `shipment-101` 到 `shipment-104`，每条各一行 | 84 | 4 |

适用范围是本地 ASCII 追加文件、单分区、显式等待位点提交和正常停止 Worker。FileStream 的恢复会按字节跳过已读位置，实验使用 ASCII 和 LF 保持计数一致。生产接入时需用实际连接器演练异常退出后的重投，并核对目标端的幂等行为。

## 官方资料

- [Apache Kafka 下载](https://kafka.apache.org/community/downloads/) · [Kafka Quickstart](https://kafka.apache.org/quickstart/) · [Kafka Connect 4.4 用户指南](https://kafka.apache.org/44/kafka-connect/user-guide/)
- [4.4.0 standalone 配置](https://github.com/apache/kafka/blob/4.4.0/config/connect-standalone.properties) · [FileStream Source 示例实现](https://github.com/apache/kafka/blob/4.4.0/connect/file/src/main/java/org/apache/kafka/connect/file/FileStreamSourceTask.java) · [Sink 位点字段](https://github.com/apache/kafka/blob/4.4.0/connect/runtime/src/main/java/org/apache/kafka/connect/util/SinkUtils.java)
