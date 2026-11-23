# 中间件学习路线

按手头的业务场景选择一条路线。消息路线可以直接运行仓库的 RocketMQ 和 Spring Modulith 实验，其余路线可沿组件分册的接入步骤，在测试环境完成练习。

| 场景 | 留下的验证结果 |
| --- | --- |
| [订单落库后发消息](#消息投递与事务) | 按订单 ID 核对本地事务、回查结果和消费日志 |
| [同一服务内订单支付后发积分](../labs/spring-modulith/README.md) | 核对已支付订单、失败投递重投和积分流水 |
| [商品详情读得快、列表搜得到](#缓存与检索) | 分别记录源数据更新后缓存和搜索结果的刷新时间 |
| [关单、对账和历史补跑](#任务调度与补跑) | 对同一业务日期执行两次，核对最终结果与重复处理次数 |
| [把数据库变更同步到下游](#数据同步与恢复) | 对账全量与增量数据，记录断开重连后的恢复位置 |
| [下游变慢或发布新版本](#服务保护与灰度) | 比较稳定版本和灰度版本的路由结果、延迟和错误率 |
| [定位一次请求在哪一跳变慢](#指标日志与链路) | 从异常指标找到样本链路，再关联同一次请求的日志 |

## 消息投递与事务

先读 [RocketMQ 事务消息](components/messaging.md#订单事务消息)，弄清半消息、本地事务和回查的先后关系。需要比较消费确认与历史重放时，再看 [RabbitMQ](components/messaging.md#rabbitmq) 和 [Kafka](components/messaging.md#apache-kafka)。选型时分别记录顺序约束的范围、失败后的重试方式和允许回放的时间窗口。

按[本地实验步骤](../README.md#run-locally)启动服务。用 `docker compose ps -a` 查看 `broker` 和 `topic-init` 的状态，确认 Broker 健康且主题初始化以状态码 0 退出，再分别发出三种请求。每次保存响应中的 `orderId`，用它关联查询结果和应用日志。

| 请求中的 `outcome` | 对照结果 |
| --- | --- |
| `COMMIT` | 查询订单返回 200，随后可在应用日志中看到该订单的消费记录。 |
| `ROLLBACK` | 查询订单返回 404，应用记录该订单的回滚。 |
| `UNKNOWN` | 查询订单返回 200。等待应用记录回查结果为 `COMMIT`，随后核对该订单的消费记录。 |

对照[事务监听器](../src/main/java/io/github/rocketmqtx/transaction/OrderTransactionListener.java)看回查如何读取本地事务结果，再看[消费实现](../src/main/java/io/github/rocketmqtx/consumer/OrderMessageConsumer.java)如何用消息 ID 和 `consumed_event` 的主键处理重复投递。如果还需要处理生产者重复发出的同一业务事件，练习为该事件选择稳定的业务幂等键。关单或对账任务也可以复用这个思路。

需要按订单依次处理创建、支付和发货时，运行 [RocketMQ 订单顺序消息实验](components/messaging.md#订单顺序消息)，按订单核对事件序列、队列号和队列位点。

## 缓存与检索

用同一批测试商品分别练习 [Redis](components/search-cache.md#redis) 的按键读取和 [Elasticsearch](components/search-cache.md#elasticsearch) 的关键词检索。有进程内热点数据时，再比较 [Caffeine](components/search-cache.md#caffeine) 和共享缓存的更新方式。

先查询商品详情和列表，修改源数据中的商品名称，再分别触发缓存失效和索引更新。记录两条读路径何时返回新值，并检查缓存被清理后首次读取是否能从数据源补齐。分别为详情和搜索结果定义可接受的陈旧时间。

## 任务调度与补跑

订单创建后只需在付款截止时触发一次状态检查时，运行 [RocketMQ 订单定时消息实验](components/messaging.md#订单定时消息)，对比分别记录的发送、计划投递和消费时间。

从 [Quartz](components/scheduling.md#quartz) 的触发器和错过触发策略开始，再看 [XXL-JOB](components/scheduling.md#xxl-job) 或 [PowerJob](components/scheduling.md#powerjob) 的执行器和失败重试。需要多个任务按依赖补数时，参考 [DolphinScheduler](components/scheduling.md#apache-dolphinscheduler) 的工作流。

如果定时对账与人工补跑可能同时运行，先做 [Quartz 同一 JobKey 并发限制实验](components/scheduling.md#按-jobkey-串行执行对账任务)，观察同一门店的补跑何时开始，以及另一家门店能否同时执行。

选一个按业务日期执行的测试对账任务，先让其中一批失败，再用相同业务日期补跑。核对失败批次能恢复、已完成批次的业务结果只生效一次，并保留任务参数、批次范围和执行日志。

## 数据同步与恢复

先走一遍 [SQL 数据同步](components/data-pipelines.md#sql-数据同步)的全量、增量和校验步骤。需要把变更送入事件流时，阅读 [Debezium](components/data-pipelines.md#debezium) 与 [Kafka Connect](components/data-pipelines.md#apache-kafka-connect)。需要同步或转换数据时，可沿 [Flink CDC](components/data-pipelines.md#flink-cdc) 的 Pipeline 接入步骤练习。

在测试表完成首次全量后，各做一次插入、更新和删除。记录连接器位点，暂停任务并产生新变更，再恢复任务。核对源表和目标表的主键集合、关键字段和删除结果，并观察积压是否回落。行数可以先用来筛查差异，再按主键定位不一致。

## 服务保护与灰度

发布前要确认环境覆盖参数合法时，运行 [Helm Schema 实验](../labs/helm-values-schema/README.md)。用本地渲染核对副本数和 Service 端口，并确认参数拼错或越界时 Helm 会报错。

新版应用依赖订单表新增字段时，运行 [Spring Boot + Flyway 启动迁移](../labs/flyway/README.md)，核对历史数据在应用就绪时可读、重启不重复执行，以及修改已执行脚本会阻止启动。

先用 [Sentinel](components/service-tools.md#sentinel) 或 [Resilience4j](components/service-tools.md#resilience4j) 练习限流、熔断或调用隔离。发布时用 [Kubernetes 探针](components/release-tools.md#kubernetes-probes)观察实例何时可接流量，再参考[全链路灰度的逐跳验证](components/traffic-routing.md#逐跳验证)和 [Argo Rollouts](components/release-tools.md#argo-rollouts) 设置观察窗口。

让测试下游固定增加延迟，记录正常、保护触发和恢复三个阶段的延迟、错误和调用次数。发出有灰度标记和无灰度标记的请求，逐跳核对实例版本。让灰度版本出现可控错误，确认中止发布后流量回到稳定版本。

## 指标、日志与链路

告警上线前，先运行 [Prometheus 告警规则实验](../labs/prometheus-rules/README.md)，用固定样本核对短暂故障不触发、持续故障触发、恢复解除，以及等待时间重置。通过后再接真实抓取和 [Alertmanager](components/observability-tools.md#alertmanager) 通知。

如果 Java 堆栈在下游被拆成多条日志，先用 [Fluent Bit 多行日志实验](../labs/fluent-bit-java-multiline/README.md) 核对事件边界。再跑 [Fluent Bit + Loki](../labs/fluent-bit-java-multiline/README.md#搭配-loki-集中检索完整堆栈)，按服务和错误正文查回一条完整的订单异常。

用 [Prometheus](components/service-tools.md#prometheus) 找出错误率或延迟变化的时间段，用 [OpenTelemetry](components/service-tools.md#opentelemetry) 和 [Tempo](components/observability-tools.md#grafana-tempo) 定位耗时的调用，再用 [Loki](components/distributed-logging.md#grafana-loki) 按时间和服务筛选日志，并用 Trace ID 核对同一次请求。

先确认应用日志包含 Trace ID，再给测试下游的一条调用加延迟。观察指标变化，找出变慢的 span，并关联调用两端的日志。若请求经过消息队列，也核对生产端到消费端的追踪上下文是否成功传递。

[查看完整组件目录](components/README.md)
