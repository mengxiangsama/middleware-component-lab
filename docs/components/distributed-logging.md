# 分布式日志组件

本页收录日志采集、解析转发与集中查询组件的常用入口。示例用测试日志，部署和插件配置以对应版本的文档为准。

## Fluent Bit

Fluent Bit 负责从应用文件或容器采集日志，再解析、过滤和转发。Java 堆栈需要保留为完整事件时，可以在采集阶段完成多行合并，再交给日志存储。

- **采集与解析**｜用 Tail 追踪文件，用单独的 `db` 保存读取偏移。JSON Lines 可用 JSON parser。Docker 或 CRI 拆分的日志用 `multiline.parser: docker, cri` 复原片段。对应用堆栈则选择与实际日志匹配的多行规则，先用连续两条异常测试分组。核对轮转策略与长行设置，`skip_long_lines` 会丢弃超长行并继续读取。 [Tail 输入](https://docs.fluentbit.io/manual/data-pipeline/inputs/tail) · [多行解析](https://docs.fluentbit.io/manual/data-pipeline/parsers/multiline-parsing)
- **过滤与输出**｜输入的 Tag 用于匹配 Filter 和 Output。结构化日志可用 Modify 移除顶层敏感字段。密钥、Cookie 或手机号也可能出现在原始 `message`、嵌套对象和未解析日志中，要在源端拦截或按实际结构过滤。确认输出后可换用 Loki 等目标插件。 [Modify Filter](https://docs.fluentbit.io/manual/data-pipeline/filters/modify) · [Loki Output](https://docs.fluentbit.io/manual/data-pipeline/outputs/loki)
- **断线与丢弃**｜用 `storage.path` 和输入 `storage.type: filesystem` 配置文件缓冲。各输出可用 `storage.total_limit_size` 限制积压，达到该上限时会丢弃该输出队列中最旧的 chunk。记录输出的 `Retry_Limit`，核对重试用尽及不可重试错误如何处理。Tail 的 `db` 仅记录读取位置，缓冲与下游接收情况需另外验证。 [缓冲](https://docs.fluentbit.io/manual/data-pipeline/buffering) · [重试](https://docs.fluentbit.io/manual/administration/scheduling-and-retries)
- **最小用法**｜保存下方配置为 `fluent-bit.yaml`，在测试文件写入 `{"message":"order created","access_token":"sample-only"}` 一行，使用 `fluent-bit -c fluent-bit.yaml` 查看 stdout。检查保留 `message` 且移除 `access_token`。再对实际输出分别验证下游断线恢复、进程重启与文件轮转，并统计缺失、重复和跳过的记录。 [YAML 配置](https://docs.fluentbit.io/manual/administration/configuring-fluent-bit/yaml)

```yaml
parsers:
  - name: lab_json
    format: json
pipeline:
  inputs:
    - name: tail
      path: /tmp/middleware-lab.log
      db: /tmp/middleware-lab-tail.db
      read_from_head: true
      parser: lab_json
      tag: lab.file
  filters:
    - name: modify
      match: lab.file
      Remove: access_token
  outputs:
    - name: stdout
      match: lab.file
```

### 合并 Java 异常堆栈

用 Tail 的 `multiline.parser` 识别异常首行和续行。运行 [Fluent Bit 多行日志实验](../../labs/fluent-bit-java-multiline/README.md)，核对两个相邻 Java 异常分别生成一条 JSON 事件，且普通日志、堆栈帧和嵌套原因完整保留。

### 搭配 Loki 集中检索完整堆栈

排查订单失败时，用 Fluent Bit 读取应用文件、合并堆栈并附上服务与环境标签，再通过 `POST /loki/api/v1/push` 发给 [Loki](#grafana-loki)。Loki 按标签组织日志，排查时用 LogQL 在订单服务范围内按错误正文定位，再查看同一条事件里的异常原因和堆栈。[对接配置与联调步骤](../../labs/fluent-bit-java-multiline/README.md#搭配-loki-集中检索完整堆栈)。

## Logstash

- **常用接入**｜`input → filter → output`。可用 file input 追踪本地日志，或用 Beats、HTTP input 接收事件。JSON 可在输入使用 codec 解码，文本常用 grok 或 dissect 提取字段，随后用 date 解析时间，用 mutate 归一字段和删除敏感数据。stdout 可检查结果，Elasticsearch output 可用于写入索引或数据流。 [处理流程](https://www.elastic.co/docs/reference/logstash/how-logstash-works) · [Elasticsearch Output](https://www.elastic.co/docs/reference/logstash/plugins/plugins-outputs-elasticsearch)
- **多行与敏感字段**｜单源文件可用 multiline codec 合并 Java 堆栈。Beats 等接收多个主机的输入应在发送端先完成合并，以免主机之间的日志片段混入同一事件。使用 `remove_field` 或 `gsub` 时要检查 `message`、`[event][original]` 及解析失败路径。Persistent Queue 位于 filter 之前，输入事件可能以原始内容进入队列。敏感信息应在产生时过滤，并核对队列和死信的内容与访问权限。 [多行 Codec](https://www.elastic.co/docs/reference/logstash/plugins/plugins-codecs-multiline) · [Mutate Filter](https://www.elastic.co/docs/reference/logstash/plugins/plugins-filters-mutate) · [Persistent Queue](https://www.elastic.co/docs/reference/logstash/persistent-queues)
- **积压与死信**｜打开 `queue.type: persisted` 后，恢复时会重送未处理完成的事件。队列达到 `queue.max_bytes` 时对输入施加背压。核对输入是否支持确认、checkpoint 间隔和单机磁盘边界。DLQ 适用于受支持的不可重试错误，例如 Elasticsearch output 的部分文档错误。有明确错误的网络中断通常按输出插件的重试策略处理。检查死信上限、清理及重读入口。 [Persistent Queue](https://www.elastic.co/docs/reference/logstash/persistent-queues) · [Dead Letter Queue](https://www.elastic.co/docs/reference/logstash/dead-letter-queues)
- **最小用法**｜将配置保存为 `lab.conf`，运行 `bin/logstash -t -f lab.conf` 检查配置，随后用 `bin/logstash -f lab.conf` 读入上面的测试 JSON。stdout 应保留 `message` 并移除顶层样例字段。接入实际输出后，制造断线、解析错误和数据映射错误，检查积压、恢复与死信去向。 [命令行](https://www.elastic.co/docs/reference/logstash/running-logstash-command-line) · [Stdin Input](https://www.elastic.co/docs/reference/logstash/plugins/plugins-inputs-stdin)

```text
input { stdin { codec => json } }
filter { mutate { remove_field => ["access_token"] } }
output { stdout { codec => rubydebug } }
```

## Grafana Loki

Loki 负责集中存储和查询日志。已使用 Fluent Bit 采集应用文件时，沿用同一条采集链路即可送入 Loki。示例见 [搭配 Fluent Bit 采集和查询 Java 堆栈](#搭配-loki-集中检索完整堆栈)。

- **写入与存储**｜聚合日志并索引各 Stream 的标签组合，日志内容存入 chunk。由客户端或采集器通过 `POST /loki/api/v1/push` 写入，在 Grafana Explore 选择 Loki 数据源查看，或用 `GET /loki/api/v1/query_range` 查询时间范围。检查租户配置及实际鉴权入口。用 Compactor 配置和验证保留期。 [HTTP API](https://grafana.com/docs/loki/latest/reference/loki-http-api/) · [存储](https://grafana.com/docs/loki/latest/operations/storage/) · [日志保留](https://grafana.com/docs/loki/latest/operations/storage/retention/)
- **标签与敏感字段**｜索引标签优先选服务名、环境等低基数且值域稳定的字段。Trace ID 或订单号可按需保留在正文或受支持的结构化元数据中。凭证与隐私数据在入库前删除或脱敏，同时检查标签、结构化元数据和正文。 [标签基数](https://grafana.com/docs/loki/latest/get-started/labels/cardinality/)
- **最小用法**｜写入一条标签为 `service_name=orders` 和 `environment=lab` 的 JSON 日志，正文示例为 `{"message":"payment callback failed","level":"ERROR"}`。用下方 LogQL 先缩小 Stream 和行范围，再解析 JSON 并筛选级别。调试时验证时间范围、标签、字段名称和 `__error__` 解析错误。 [LogQL 日志查询](https://grafana.com/docs/loki/latest/query/log_queries/)
- **故障验证**｜暂停写入入口，观察客户端缓冲与重试。恢复后按时间戳和测试事件标识核对缺失、重复及迟到日志。检查限流、超出允许的时间范围和非法标签产生的拒绝原因，再对照发送端的重试与丢弃统计。 [排查写入问题](https://grafana.com/docs/loki/latest/operations/troubleshooting/troubleshoot-ingest/)

```logql
{service_name="orders", environment="lab"} |= "payment callback" | json | __error__="" | level="ERROR"
```
