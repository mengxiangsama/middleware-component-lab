# 维护记录

## 2026-11-23 · Prometheus 告警的 pending 状态与等待重置

**检查范围与选题理由**　检查现有教程、组件目录和近期提交。近期 Redis 覆盖较多，RabbitMQ、NATS、Prometheus 等组件的可运行实验较少。发布前重新同步 `main`，发现 [`05bb714`](https://github.com/mengxiangsama/middleware-component-lab/commit/05bb7140a785da902b83dd7fe4e63d79cc14f4d8) 已加入 Prometheus 基础告警测试，因此本轮复用该实验，补充尚未覆盖的等待状态与重置行为。

| 组件 | 候选实用教程 | 本轮取舍 |
| --- | --- | --- |
| Prometheus | 显式 pending 断言、短暂故障与等待重置 | 本轮选择；已有 5 个基础告警断言，适合补足状态和边界验证 |
| RabbitMQ | 发布确认与 `mandatory` 不可路由返回 | 后续可用真实 Broker 对照两个结果 |
| NATS JetStream | 持久拉取消费者的确认超时、重投及恢复 | 后续需要真实服务端和客户端联调 |
| Liquibase | changeset 升级与回滚 | 近期刚补 Flyway 结构迁移，先补可观测性场景 |

**官方依据**　对照 [Prometheus 官方仓库](https://github.com/prometheus/prometheus)、[3.16.0 官方测试样例](https://github.com/prometheus/prometheus/blob/v3.16.0/cmd/promtool/testdata/unittest.yml)、[规则测试文档](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/)和[告警规则语义](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/)。同时查阅了 RabbitMQ 的[发布确认教程](https://www.rabbitmq.com/tutorials/tutorial-seven-java)与[确认机制](https://www.rabbitmq.com/docs/confirms)，其发布与路由结果对照也是后续候选。

**本轮改动**　扩展 [Prometheus 实验](../labs/prometheus-rules/README.md)，保留原规则和原有五个告警时间点。增加两组故障样本、健康实例及其他 job 的筛选对照、三个显式状态断言，并提供完整教程和统一运行脚本。更新首页、组件目录和学习路线，增加独立的 GitHub Actions 规则验证 job。

**实测环境**　macOS 26.5 arm64，Docker Engine 27.3.1；容器内 `promtool 3.16.0`，平台 `linux/arm64`。镜像为 `prom/prometheus:v3.16.0`，拉取所得 RepoDigest 为 `sha256:e04987641b57704f408e31f6373ba4138ecf27cb4a165d24aabb6c38fddd0f06`。

| 验证 | 实际结果 |
| --- | --- |
| `sh labs/prometheus-rules/verify.sh` | 退出码 0；发现 1 条规则，三组样本全部成功 |
| 告警与状态断言 | 12 个告警断言、3 个 `ALERTS` 状态断言；持续故障于 `2m` pending、`3m` firing、`4m` 解除；健康实例和其他 job 不误报 |
| 故意改错的对照验证 | 仅在临时副本把 `for: 2m` 改为 `for: 1m`；语法检查仍退出 0，规则测试退出 1，检出 `2m`、`4m` 提前触发及状态不符；临时副本已删除 |
| 根项目回归 `./mvnw -B verify` | 使用 OpenJDK 17.0.16、Maven 3.9.11，构建成功；10 项测试中 7 项通过、3 项需要 Broker 的实验按原条件跳过，无失败或错误 |
| Shell 语法及 Git 差异检查 | `sh -n labs/prometheus-rules/verify.sh`、`git diff --check` 均退出 0 |

**验证边界**　规则实验使用合成指标样本，并由真实 `promtool` 求值；未验证真实抓取、服务发现和 Alertmanager 通知。本地只运行了 arm64 镜像，远端 CI 的结果以该提交的 Actions 记录为准。提交链接在本轮维护回复中记录。

## 2026-11-23 · Helm 环境覆盖参数校验

盘点 [`05bb714`](https://github.com/mengxiangsama/middleware-component-lab/commit/05bb7140a785da902b83dd7fe4e63d79cc14f4d8) 的组件目录、现有实验和最近 30 条提交。近期已补 Redis、RocketMQ、Netty、Spring Modulith 和 Prometheus 等主题。交付分册对 Helm 只有通用操作说明，缺少可直接运行的参数校验示例。

| 组件 | 调研到的实用缺口 | 取舍 |
| --- | --- | --- |
| Helm | 合并环境覆盖值后提前检查类型、范围和拼写 | 本轮选择，只需真实 Helm CLI 即可复现成功与失败 |
| Fluent Bit | 合并相邻的 Java 多行异常并核对事件边界 | 手册已有多行采集说明，可单独补一轮输入到输出的实测。[官方示例](https://docs.fluentbit.io/manual/data-pipeline/parsers/multiline-parsing#java) |
| RabbitMQ | 消费者拒绝后核对死信路由和 `x-death` | 需要启动 Broker 并联调实际投递。[官方死信文档](https://www.rabbitmq.com/docs/dlx) |

新增 [Helm Schema 实验](../labs/helm-values-schema/README.md)，更新组件目录、交付手册和学习路线。Schema 规则及合并次序对照了 [Helm 官方文档](https://helm.sh/docs/topics/charts/#schema-files)与 [v4.3.2 上游测试 Chart](https://github.com/helm/helm/blob/v4.3.2/pkg/cmd/testdata/testcharts/chart-with-schema/values.schema.json)。

在 macOS arm64 上运行经官方 SHA-256 校验的 Helm 4.3.2。执行 `sh labs/helm-values-schema/verify.sh`，默认和环境覆盖的 `lint` 与渲染均通过，分别得到 1 副本、端口 80 和 2 副本、端口 8080。端口类型不符、端口 65536、`service.prt` 和零副本均被 Schema 拒绝。运行根项目 `./mvnw -B verify` 成功，7 项通过，3 项按现有条件跳过。

验证范围是本地 Chart 校验与渲染。集群 API、准入和镜像运行未纳入本轮实验。

## 2026-11-23 · Fluent Bit 合并 Java 异常堆栈

盘点 [`292ec52`](https://github.com/mengxiangsama/middleware-component-lab/commit/292ec52014f5a67e23063d59cb30c9359be6c573) 的组件目录、实验和最近 30 条提交。近期补过多项消息、缓存和服务治理主题，分布式日志分册仍只有通用说明，没有可运行的多行分组实验。

| 组件 | 调研到的实用缺口 | 取舍 |
| --- | --- | --- |
| Fluent Bit | 文本文件中相邻 Java 异常的事件边界 | 本轮选择，用官方镜像核对真实 Tail 输入和 stdout 输出 |
| RabbitMQ | 拒绝消费后核对死信路由及 `x-death` 元数据 | 适合另用 Broker 与客户端联调。[官方死信文档](https://www.rabbitmq.com/docs/dlx) |
| NATS JetStream | 持久拉取消费者超时重投及重连 | 需要运行服务端与客户端并核对投递次数。[官方拉取消费者示例](https://docs.nats.io/learn/jetstream/pull-consumers) |

新增 [Fluent Bit 多行日志实验](../labs/fluent-bit-java-multiline/README.md)，同步根 README、组件目录、日志分册和学习路线。对照了 [Fluent Bit 仓库](https://github.com/fluent/fluent-bit)、[官方多行配置示例](https://docs.fluentbit.io/manual/data-pipeline/parsers/multiline-parsing#configuration-example)和 [5.2.0 的 Java 内置规则](https://github.com/fluent/fluent-bit/blob/v5.2.0/src/multiline/flb_ml_parser_java.c)。

在 macOS arm64 的 Docker Engine 27.3.1 上，用官方 Fluent Bit 5.2.0 镜像实测。镜像平台为 `linux/arm64`，拉取所得摘要为 `sha256:e2718c47aaab7d99587fdffe78818549bdd09dee5e30a9199d2ff3da6e2c7628`。运行 `sh labs/fluent-bit-java-multiline/verify.sh` 成功，12 行输入按 1、7、3、1 行分成四条事件，逐行核对无丢失。关闭多行解析的对照运行输出 12 条单行事件。根项目 `./mvnw -B verify` 构建成功，7 项通过，3 项按原条件跳过。

最初以异常堆栈结尾时，实测 `exit_on_eof` 使最后一段未输出。最终样例用一条普通日志结束缓冲。长期追踪、轮转和日志存储接收留给接入环境验证。

## 2026-11-23 · Fluent Bit 与 Loki 联调

基于 [`c0c3f6b`](https://github.com/mengxiangsama/middleware-component-lab/commit/c0c3f6b66802a7978795a5cd120ffd830e9613a2) 的 Fluent Bit 示例补齐从文件采集到集中查询的路径。组件目录和日志分册分别从 Fluent Bit 与 Loki 链到同一份[组合说明](../labs/fluent-bit-java-multiline/README.md#搭配-loki-集中检索完整堆栈)，根 README 提供入口和运行命令。

在 Docker Engine 27.3.1 的 `linux/arm64` 容器中，用 Fluent Bit 5.2.0 真实推送到 Loki 3.7.10。运行 `sh labs/fluent-bit-java-multiline/verify-loki.sh` 成功。Loki 返回四条事件，按时间排序后逐行对照原文件，12 行全部保留。LogQL 按服务、环境及错误正文筛选，命中一条结账异常并返回完整堆栈及 `SocketTimeoutException`。根项目 `./mvnw -B verify` 构建成功，7 项通过，3 项按原条件跳过。

对照 [Fluent Bit 原生 Loki 输出](https://docs.fluentbit.io/manual/data-pipeline/outputs/loki)、[Loki 3.7.10 镜像配置](https://github.com/grafana/loki/blob/v3.7.10/cmd/loki/loki-docker-config.yaml)和 [Loki 查询接口](https://grafana.com/docs/loki/latest/reference/loki-http-api/#query-logs-within-a-range-of-time)。本次验证覆盖单机写入和 API 查询，未纳入 Grafana 界面、集群及生产鉴权。

## 2026-11-23 · Spring Boot 与 Flyway 启动迁移

盘点 [`eeb5151`](https://github.com/mengxiangsama/middleware-component-lab/commit/eeb5151f63fd74c0936a0c4f96cf2fc6c56551cf) 的组件目录、实验和最近 30 条提交。对比 Spring Cache + Caffeine 的商品改价失效与 Spring Boot + Flyway 的发布前迁移。缓存近期已有多项示例，现有 Flyway 实验只覆盖手动调用，优先补齐应用启动自动迁移与失败阻断。[Spring Boot 的 Caffeine 接入](https://docs.spring.io/spring-boot/3.5/reference/io/caching.html#io.caching.provider.caffeine) · [Spring Boot 自动迁移](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html)。

复用订单表 V1、V2 SQL，使用 Spring Boot 3.5.16、Flyway 11.8.2、H2 2.3.232 和 OpenJDK 17.0.16，启动真实应用上下文并验证同一个临时文件库。就绪时读到订单 `101`、金额 `19.90` 和状态 `PENDING`，重启仍只有 V1、V2 两条成功版本。改动已应用的 SQL 临时副本后校验失败，没有发出 `ApplicationReadyEvent`。运行 `./mvnw -B -f labs/flyway/pom.xml clean verify`，独立实验 3 项测试全部通过。

同步根 README、组件目录、Spring Boot 和 Flyway 条目及学习路线。[教程与验证命令](../labs/flyway/README.md)。实验范围为单应用上下文和内嵌 H2，无 HTTP 服务，未验证 MySQL、PostgreSQL 或多副本滚动发布。

## 2026-11-23 · Spring Cache 与 Caffeine 商品改价

盘点 [`3c58f8e`](https://github.com/mengxiangsama/middleware-component-lab/commit/3c58f8ebbc33acf91d7cc10a76aa79af6a81f917) 的组件目录、实验和最近 30 条提交。比较 Caffeine 商品改价后的缓存失效、Quartz 使用 JDBC 保存触发器和 Prometheus 联调抓取。现有 Caffeine 教程只有原生异步刷新，近期两次提交已补 Actuator 和指标端点。本轮选 Caffeine，验证数据库事务何时影响本机缓存。[Spring 缓存示例](https://github.com/spring-guides/gs-caching) · [Quartz JDBC 配置](https://docs.spring.io/spring-boot/3.5/reference/io/quartz.html)

在 OpenJDK 17.0.16、Spring Boot 3.5.16、Caffeine 3.2.4 和 H2 2.3.232 下执行 `./mvnw -B -f labs/spring-cache-caffeine/pom.xml clean verify`，3 项集成测试通过。首读回填后确实命中 Caffeine，提交后只清除商品 101 的旧缓存并读到 `24.90`，回滚时数据库和原缓存仍是 `19.90`。行为与 [Spring 事务感知缓存说明](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/cache/transaction/TransactionAwareCacheDecorator.html)一致。

已同步根 README、组件目录和学习路线。[教程与运行方法](../labs/spring-cache-caffeine/README.md)。实验使用单 JVM 和内嵌 H2，跨实例失效和并发回填顺序未验证。

## 2026-11-23 · Redis Pub/Sub 广播 Caffeine 商品改价失效

盘点 [`c707838`](https://github.com/mengxiangsama/middleware-component-lab/commit/c70783808db7effc5d92c1913562494c3ad2de55) 的组件目录、实验和最近 30 条提交。Redis 已有 CLI 广播示例，Caffeine 已有单实例提交失效。对照了广播失效、单实例同键合并和共享二级缓存，本轮侧重将前两项接成一条可验证的改价链路，回应多实例读旧价和热点同时回源的问题。[Spring Data Redis 的发布订阅](https://docs.spring.io/spring-data/redis/reference/3.5/redis/pubsub.html) · [Spring Cache 同步加载](https://docs.spring.io/spring-framework/reference/6.2/integration/cache/annotations.html#cache-annotations-cacheable-synchronized)

在 macOS arm64 和 OpenJDK 17 上使用经官方 SHA-256 核对后编译的 [Redis 7.4.12](https://github.com/redis/redis/releases/tag/7.4.12)，绑定本机回环端口。两个独立 Spring Boot 上下文各有一个 Caffeine 实例，共用真实 Redis 和内嵌 H2。改价回滚保留两份旧缓存，提交后两份都清商品 101、保留商品 102。24 个并发调用均读取 `24.90`，两份 Caffeine 各成功加载一次。单实例测试也确认事务内读取的未提交价格不会写进缓存。

两实例联调和四项单实例回归均通过。根 README、组件目录和学习路线提供了实验入口和命令。[双实例运行方法](../labs/spring-cache-caffeine/README.md#多实例改价通知与回源合并)。本轮未验证跨进程压测、Redis 断线恢复或全局只回源一次。

## 2026-11-23 · Spring Boot 与 Quartz JDBC 重启补跑

盘点 [`8f9d19c`](https://github.com/mengxiangsama/middleware-component-lab/commit/8f9d19c7aecd478f364c644a563fd74c606b217f) 的组件目录、实验和最近 30 条提交。比较了 Quartz JDBC 持久化与 Prometheus 实际抓取。仓库近期补过指标导出和告警测试，Quartz 仍只有内存调度器的并发教程，本轮补门店对账登记后如何跨重启保留计划。[Spring Boot Quartz 文档](https://docs.spring.io/spring-boot/3.5/reference/io/quartz.html) · [Prometheus 抓取配置](https://docs.spring.io/spring-boot/3.5/reference/actuator/metrics.html#actuator.metrics.export.prometheus)

使用 OpenJDK 17.0.16、Spring Boot 3.5.16、Quartz 2.5.2 和文件 H2 2.3.232 运行 `./mvnw -B -f labs/quartz-jdbc/pom.xml clean verify`，集成测试通过。原应用上下文关闭后保留了 H2 文件，第二个上下文从同库读回原计划时间和 `FireNow` 策略。启动调度后收到一次性触发器的 misfire 回调，补写一条 `store-a / 2026-11-22` 对账执行记录，触发器已清除。

教程已链接根 README、组件目录和学习路线。[实验与验证命令](../labs/quartz-jdbc/README.md)。已过去的触发时刻用于模拟错过计划，实验未验证运行中强制中断恢复或多实例故障转移。

## 2026-11-24 · 16:13–16:43 手动维护

从 `63cc7ec` 起检查组件目录、实验及近期提交。优先补缺少可运行示例的组件组合，已有教程则补一项独立能力。各实验链接首页或组件索引，教程包含适用场景、接入步骤、验证方法和官方资料。

| 组件或功能 | 选题理由 | 本地实测 | 提交 |
| --- | --- | --- | --- |
| PostgreSQL 逻辑复制 | 订单报表库需要收到增量变更。 | 两个 PostgreSQL 17.12 实例完成首次复制、增删改同步、暂停恢复和复制槽清理。 | [3570ba8](https://github.com/mengxiangsama/middleware-component-lab/commit/3570ba8c24137c49999618292d6d2d5c9344c383) |
| Meilisearch 商品搜索 | 商品列表需要按分类、库存和价格查找。 | Meilisearch 1.58.0 的索引任务和查询通过，商品下架后从可售结果中移除。 | [d97b5a3](https://github.com/mengxiangsama/middleware-component-lab/commit/d97b5a3fb0b128ba4b556017ab968c35461c87fd) |
| gRPC + Protobuf | 订单服务调用库存时需要限定等待时间。 | 两个 Java 服务完成正常库存查询，短截止时间返回 `DEADLINE_EXCEEDED`，下游观测到取消。 | [346e1ac](https://github.com/mengxiangsama/middleware-component-lab/commit/346e1ac7dd445395c45e57e68def9509b61d39f7) |
| ClickHouse + chDB | 按业务日期统计订单，需要合并多批数据和补算历史记录。 | chDB 4.0.2 中，两个写入批次与历史回填所得报表和明细查询一致。 | [a8b36c1](https://github.com/mengxiangsama/middleware-component-lab/commit/a8b36c159f071120956ab2eb934d6dc42d5b48f2) |
| SeaweedFS + AWS SDK for Go | 浏览器直传和下载需要限于指定对象。 | SeaweedFS 4.55 的预签名 PUT/GET 往返校验通过，错键和匿名请求返回 403。 | [dc45e69](https://github.com/mengxiangsama/middleware-component-lab/commit/dc45e69ddccb058d6ec849575510fac08b05cd57) |
| NGINX + SSE | 前端需要经反向代理及时看到订单状态。 | NGINX 1.30.8 在上游关闭前送达 `packed`，随后收到 `shipped` 和 EOF。 | [f36a80c](https://github.com/mengxiangsama/middleware-component-lab/commit/f36a80c5c251a1ae65c193638905f13abfb61c25) |
| Artemis + Jakarta JMS | 消息处理反复失败后需要保留内容供排查。 | 回滚两次后，Artemis 2.44.0 把原消息送入死信队列，后续正常消息一次消费即提交。 | [9d8cdae](https://github.com/mengxiangsama/middleware-component-lab/commit/9d8cdae1bb9942414ee294f604828c6acad89438) |
| Consul + HTTP 健康检查 | 调用端需要发现可用实例并避开故障实例。 | 实例 B 返回 503 后从健康发现结果移除，恢复后重新被发现，实例 A 继续响应。 | [6289793](https://github.com/mengxiangsama/middleware-component-lab/commit/62897930908a5e314838451a6e4831eaefdf2cb3) |
| Debezium + PostgreSQL | 下游服务需要读取订单的变更内容。 | 实际 `pgoutput` 连接产出快照、插入、更新与删除事件，主键和变更前后值均符合预期。 | [604c60e](https://github.com/mengxiangsama/middleware-component-lab/commit/604c60e9f0efc9490322e967aeac03b34b545ec1) |
| Flink 事件时间窗口 | 乱序订单要按事件时间汇总，过晚记录需留待核查。 | 本地 Flink 1.20.3 验得两个窗口金额为 1700 分和 1000 分，仅迟到记录 `p4` 进入侧输出。 | [9612584](https://github.com/mengxiangsama/middleware-component-lab/commit/9612584c3383d7843add87b42145bced19b5b3c0) |
| Camel File + HTTP | 批量上传需在对端确认后归档。 | 第一次 POST 返回 503，同一份 CSV 重投成功并得到 204，之后才归档，字节一致。 | [5828a42](https://github.com/mengxiangsama/middleware-component-lab/commit/5828a423594bbe6e7eadfc48d92f766fa3a346a5) |
| Quartz + H2 | 分批任务重启后需要续接处理进度。 | 调度器更换后从同一 H2 文件读出游标 3，下次执行推进到 4。 | [abc4fc6](https://github.com/mengxiangsama/middleware-component-lab/commit/abc4fc6956451d732e66be9e5384cfc82576aa0a) |
| Redisson + Valkey | 多个 Java 实例需要共用接口限流额度。 | 两个独立 JVM 各取一个配额，第三次请求被拒绝，剩余 0。 | [a1aeac4](https://github.com/mengxiangsama/middleware-component-lab/commit/a1aeac4c677d09b768652a59915c51d66531c88f) |
| DuckDB + Parquet | 核对指定日期订单时希望跳过其他日期的明细文件。 | 两个分区写入成功，所选日期两个渠道金额吻合，物理计划显示 `Scanning Files: 1/2`。 | [9435a49](https://github.com/mengxiangsama/middleware-component-lab/commit/9435a49c3b246758d1e485683756af9144847292) |

上述结果来自本地实验。SeaweedFS 和 Valkey 首次主工作区复测的进程启动不稳定，后续重跑通过。另补充了 ClickHouse 虚拟环境步骤和 S3 对象键说明。[文档修订](https://github.com/mengxiangsama/middleware-component-lab/commit/6220363fd06873e15c7b72e2b6a47826d5643ce7)

## 2026-11-24 · 17:29 起的手动维护

从 `b9ddac9` 起查阅 `main` 的组件目录、现有实验和近期提交。优先补齐覆盖较少的 SQLite、Caddy、MQTT、SFTP 和 Temporal 等组件组合，同时为只介绍了原理的 RabbitMQ、Apicurio Registry 和 OpenSearch 增加可复现的行为对照。各主题补上首页入口、适用场景、接入步骤、跑法和官方资料。

| 主题 | 选题理由 | 本地实测结果 | 提交 |
| --- | --- | --- | --- |
| SQLite + JDBC | 对账报表打开时仍需继续登记发货。 | 旧读事务始终看到 1200 分，新事务读到 2000 分。验证了两个写冲突错误码。 | [fb13b85](https://github.com/mengxiangsama/middleware-component-lab/commit/fb13b8525ad0ea55a615eaa5ec6aba0f12375155) |
| NATS JetStream KV + Go | 两个 Worker 的批次计数更新可能互相覆盖。 | 同 revision 的第二次写入被拒绝，重读再加 3 后计数为 15、revision 为 3。 | [c9991d6](https://github.com/mengxiangsama/middleware-component-lab/commit/c9991d6fc170a33da268dfac273b75a1e4d2a164) |
| Caddy + Python HTTP API | 本机 HTTP 服务需要 HTTPS 测试入口和路径转发。 | 客户端默认拒绝证书，加载本次 CA 后成功。后端收到原始 Host 和 HTTPS 转发信息，未匹配路径不进后端。 | [5062cbb](https://github.com/mengxiangsama/middleware-component-lab/commit/5062cbb12dbad8f6610eb4c4526fd13cfb9528df) |
| RabbitMQ + Java | 排查收到发布 ACK 后消息仍未到队列。 | 有路由消息收到 ACK 并入队，无路由消息也收到 ACK，同时返回 `312 NO_ROUTE`。 | [a73a8fb](https://github.com/mengxiangsama/middleware-component-lab/commit/a73a8fbabc34fdd5e80cfcdebb5bee0d92107ab5) |
| OpenFeature + flagd | 结算服务只向试点门店启用新版运费预估。 | 真实 gRPC 求值返回试点 `true`、普通门店 `false`，未找到开关时返回应用默认值和 `FLAG_NOT_FOUND`。 | [eaa2a0f](https://github.com/mengxiangsama/middleware-component-lab/commit/eaa2a0f22ca01e4d12a1a58dccff66165ce8d46b) |
| MySQL InnoDB + JDBC | 多个 Worker 从同一张表领取发货任务。 | A 持锁 101，B 跳过并提交 102，A 回滚后 C 领取并提交 101。 | [5e16647](https://github.com/mengxiangsama/middleware-component-lab/commit/5e1664746d46514f0b6e600dbf9350d6632463a8) |
| Apache MINA SSHD + SFTP | 合作方只应读到上传完成的整份文件。 | 不匹配的主机密钥被拒绝。SFTP v6 确认同目录原子更名，最终 CSV 的 42 字节完全一致。 | [1450f9a](https://github.com/mengxiangsama/middleware-component-lab/commit/1450f9ad2a22e8af5b2f9c383506511696810d7a) |
| Prometheus + Blackbox Exporter | 从应用外监控就绪端点，区分抓取异常与应用未就绪。 | 模拟 Actuator 的 HTTP 端点返回 200 → 503 → 200。真实 Prometheus 查到 `probe_success` 为 1 → 0 → 1，`up` 始终为 1。 | [e82e0b2](https://github.com/mengxiangsama/middleware-component-lab/commit/e82e0b22376f0cd3ab4fda84db217626af3feef8) |
| Vector + HTTP | 转发发货日志前限制字段，并能查出坏记录。 | 接收端收到 1 条仅含允许字段的发货事件，诊断输出为 2 条，只含分类和原因。 | [887e4c2](https://github.com/mengxiangsama/middleware-component-lab/commit/887e4c29116392f382357a942b9438e072730edd) |
| Apicurio Registry + Avro | 升级订单事件时检查以前写入的消息能否读。 | 带默认值的字段版本注册成功，无默认值的必填字段返回 400，已发布版本仍只有 1、2。 | [a1077dd](https://github.com/mengxiangsama/middleware-component-lab/commit/a1077dd673f520d13324f25e82461fd7a9124e3d) |
| OpenSearch + Python | 对账跨页期间仍会补录或更新订单。 | 原 PIT 保留 `O103=PAID`，新查询看到 `O105` 与 `O103=FULFILLED`。PIT 已关闭。 | [1050591](https://github.com/mengxiangsama/middleware-component-lab/commit/105059100d864e25ad95b950a0da1ced65155df6) |
| Mosquitto + Paho MQTT | 设备短时掉线后需要接回离线指令。 | 使用同一 Client ID 重连后收到一条 QoS 1 非保留消息 `mode=eco`，并确认清理会话后不会恢复。 | [ac17198](https://github.com/mengxiangsama/middleware-component-lab/commit/ac1719826566b953e92bb8e52dbf51702c96cf14) |
| Temporal + Go | 库存已写入却返回 503 时，工作流需要有限重试。 | 真实 Temporal 两次尝试，收到 503、200，工作流拿到原预留号，带幂等键的库存测试服务只写入一笔。 | [00ee42f](https://github.com/mengxiangsama/middleware-component-lab/commit/00ee42f042aca1dbd66d52562ab88707e80834de) |

Docker 存储在后半段发生元数据 I/O 错误。Apicurio 与 OpenSearch 使用官方发行包复测，Mosquitto 使用官方源码构建的本机 Broker 复测。每个实验的环境、运行命令和验证范围见各自教程。上轮延后的根项目 Maven 回归成功，11 项通过，3 项按原条件跳过。

## 2026-11-25 · Spring Batch 发货 CSV 分批导入续跑

同步 `a735e02` 后检查根 README、组件目录、教程与近期提交。现有 Quartz JDBC 教程已覆盖应用自行保存的游标，Temporal 覆盖远程 Activity 重试，Camel 覆盖整份 CSV 的 HTTP 重投。候选方向还包括给 Pulsar 的 `Key_Shared` 补上真实 Broker 路由验证。Spring Batch 此前缺少组件说明和可运行示例，本轮选择用本地文件库验证框架保存的读取位置，以及业务写入和检查点如何在同一 chunk 的事务中提交。[Spring Batch 官方失败重启示例](https://github.com/spring-projects/spring-batch/blob/v5.2.6/spring-batch-samples/README.md#fail-restart-sample) · [Pulsar 订阅类型](https://pulsar.apache.org/docs/concepts-messaging/#key_shared)

在 macOS 26.5 ARM64、OpenJDK 17.0.16、Spring Batch 5.2.6 和 H2 2.3.232 上执行 `./mvnw -B -f labs/spring-batch-chunk-restart/pom.xml clean verify` 两次，两次各 1 项通过。第二批写入后故意抛出异常。第一次执行状态为 `FAILED`，数据库保留 2 行，重新查询的读取检查点为 2。用相同标识参数创建新的执行，读取并写入余下 3 行，最终 5 行的编号和金额与 CSV 一致，状态为 `COMPLETED`。

验证范围为同一 JVM 内重建作业运行对象、重新打开 H2 文件库、受控失败回滚和续跑。根 README、组件索引和学习路线已补上入口，Spring Batch 与 H2 手册已互相链接。[运行教程](../labs/spring-batch-chunk-restart/README.md) · [提交](https://github.com/mengxiangsama/middleware-component-lab/commit/f26aea37f375da800858f755e01c4fa0bf2a97a2)

## 2026-11-26 · Pulsar Key_Shared 按订单分发发货事件

同步 `faa0e64` 后检查根 README、组件目录、现有实验和近期提交。RabbitMQ 已实测路由返回，Kafka 已实测消费位点，Pulsar 仍只有订阅模式说明。另一个候选是 Kafka Connect 的 FileStream 联调。优先选择昨日已列出的 Pulsar 缺口，因为现有消息教程尚无 Pulsar 实验，并且可以用一个真实 Broker 验证同一个订阅如何按订单分工。[Pulsar Key_Shared 语义](https://pulsar.apache.org/docs/4.0.x/concepts-messaging/#key_shared) · [Kafka Connect FileStream 官方用法](https://kafka.apache.org/44/kafka-connect/user-guide/)

在 macOS 26.5 ARM64、Docker Engine 27.3.1 和 OpenJDK 17.0.16 上运行 `sh labs/pulsar-key-shared/verify.sh`，再用 `PULSAR_LAB_PORT=16651 sh labs/pulsar-key-shared/verify.sh` 复跑。Broker 使用官方 `apachepulsar/pulsar:4.0.14` 的 `linux/arm64` 镜像，Java Client 同为 4.0.14。镜像清单摘要为 `sha256:d404b4db9b809cd4e8b65fc2120a97dbc024c222fc2d53041d900c7d5a5f1f2d`。两次各 1 项测试通过，均收到 48 条消息。两个 Java 消费者分别收到 5 和 11 个订单，每个订单的 `CREATED → PAID → SHIPPED` 完整且有序。

本轮覆盖单节点、单 Topic、稳定的两个消费者、固定范围路由和逐条确认调用。首页、组件目录、消息手册和学习路线均补上入口。[运行教程](../labs/pulsar-key-shared/README.md) · [提交](https://github.com/mengxiangsama/middleware-component-lab/commit/9f0698ce8621650e8e979010c338d14ae1b8b66f)

## 2026-11-27 · Kafka Connect FileStream 在 Worker 重启后续跑

同步 `6d8f6ee` 后检查首页、组件手册、实验和近期提交。Kafka 位点重放实验已覆盖 Java 消费者，Pulsar 昨日刚完成双消费者按键分发，掉线接管可另做验证。Kafka Connect 此前只有组件说明，本轮选用官方 FileStream 插件补齐 Source、Kafka 和 Sink 之间的文件传输，以及 Worker 重启后两端如何各自恢复位点。[Kafka Connect 4.4 用户指南](https://kafka.apache.org/44/kafka-connect/user-guide/) · [FileStream Source 实现](https://github.com/apache/kafka/blob/4.4.0/connect/file/src/main/java/org/apache/kafka/connect/file/FileStreamSourceTask.java)

在 macOS 26.5 ARM64、OpenJDK 17.0.16 和 Python 3.13.7 上运行 `python3 labs/kafka-connect-filestream/verify.py /path/to/kafka_2.13-4.4.0`。Kafka Broker、Connect Worker 和 FileStream 插件均为官方 4.4.0，二进制包按 Apache 发布的 SHA-512 校验。两次实际运行均得到同样的位点和输出。Worker 正常停止前核对到 2 行、Source 位置 42、Sink 下一条位点 2。停机期间追加 2 行，重启后最终输出 `shipment-101` 到 `shipment-104` 各一行，Source 位置为 84，Sink 下一条位点为 4。

实验覆盖真实的单节点 KRaft Kafka、一个 standalone Worker、单分区、ASCII 追加文件与正常停止恢复。首页已更新运行命令，组件索引标记联调实测，Kafka 和 Connect 手册已互相链接。[运行教程](../labs/kafka-connect-filestream/README.md) · [提交](https://github.com/mengxiangsama/middleware-component-lab/commit/3ff40952207a2cd11cf5e75c7fa4e34777c070f7)

## 2026-11-27 · 晚间半小时维护

从 `119898e` 开始，查看首页、组件手册、实验和近期提交。此前 Kafka Connect 已验证文件位点恢复，Spring Batch 已验证分批续跑，Pulsar 已验证固定分组。可观测性教程已有 JVM 指标和模拟 span，仍缺实际业务计时及真实 HTTP 传播。其余轮次优先补可在本机反复验证的任务协调、访问控制、网络故障、就绪切换和共享内存接管。各轮的官方文档、配置、运行命令和适用范围见相应教程。

| 组件组合 | 选题理由 | 实测结果 | 提交 |
| --- | --- | --- | --- |
| [Micrometer Timer + Actuator + Prometheus](../labs/micrometer-actuator-timer/README.md) | 现有 Actuator 示例只查 JVM 指标，需要练习应用业务耗时怎么进入监控查询。 | Spring Boot 3.5.16、Micrometer 1.15.12、真实 Prometheus 3.16.0。两次实测均抓取成功。通过 2 次、拒绝 1 次。通过请求在 50 ms、250 ms 和总次数分别为 1、2、2。慢检查实测约 151–155 ms。 | [92aecb5](https://github.com/mengxiangsama/middleware-component-lab/commit/92aecb51c7a6f88c5546b8c6f011d4c9fcdc78ce) |
| [OpenTelemetry Java + JDK HTTP](../labs/otel-java-http-propagation/README.md) | 原 Collector + Tempo 教程用预置 span，真实应用 HTTP 传递上下文需要单独验证。 | Java SDK 1.62.0，3 项通过。真实本机 HTTP 的 `traceparent` 连接客户端和服务端 span。两侧 Trace ID 相等，服务端指向远端父 span，缺失或无效传播头各自生成独立根 span。两侧使用内存 exporter。 | [a65f50b](https://github.com/mengxiangsama/middleware-component-lab/commit/a65f50bb93ebb2aa89bb768988e1c4aded05ad16) |
| [Pulsar Key_Shared AUTO_SPLIT + Java](../labs/pulsar-key-shared/README.md#auto_split-消费者接手) | 固定范围教程没有覆盖消费者退出后谁接手同键事件。 | Pulsar 4.0.14 官方本机包经 SHA-512 校验。三次运行均通过固定分组及接手两项测试。集成复测中 A 未确认的 `PACKED`（`6:0:-1`）在其 JVM 退出后交给 B，B 确认后收到了同键 `SHIPPED`（`6:1:-1`）。 | [10f0882](https://github.com/mengxiangsama/middleware-component-lab/commit/10f088231c3c7151d31d486054eba6d3eb1856c5) |
| [Spring Scheduling + ShedLock + JDBC](../labs/shedlock-jdbc/README.md) | Quartz 已覆盖计划持久化。普通 Spring 定时方法在多个副本上执行时，需要共享锁。 | ShedLock 7.12.0、Spring JDBC 6.2.19、H2 2.3.232。两个 Spring 上下文共享 H2 文件库，1 项测试通过并复测。A 持锁时 B 跳过且无报表写入，A 释放后新触发 B，最终按序记录 A、B。 | [6320395](https://github.com/mengxiangsama/middleware-component-lab/commit/6320395cdd5fa2c5d16409b24ba01ed208e86c6d) |
| [OPA + Python HTTP 网关](../labs/opa-shipment-authorization/README.md) | 组件目录有功能灰度，缺少按业务资源和角色做访问决策的例子。 | OPA 1.23.0，5 项 Rego 和 5 项本机 HTTP 联调通过。同仓操作员返回 200，跨仓和访客返回 403，无身份返回 401，策略服务不可用返回 503 且响应不含运单。 | [287c12f](https://github.com/mengxiangsama/middleware-component-lab/commit/287c12f677d3e8a43fed4e59226ed971dd2c62e7) |
| [Toxiproxy + Java HttpClient](../labs/toxiproxy-http-timeout/README.md) | 现有 Resilience4j 库存示例模拟 HTTP 503，补充已经到达后端但尚未收到响应的超时。 | Toxiproxy 2.13.0，注入 3,500 ms 下行延迟后约 1,001–1,002 ms 触发 `HttpTimeoutException`，后端确实收到第 2 次请求。移除延迟后 HTTP 200，累计收到 3 次请求。 | [dcd0282](https://github.com/mengxiangsama/middleware-component-lab/commit/dcd0282d03c36d7deb1fde8dd3af8b3ae89ba915) |
| [Caddy + HTTP 就绪探针](../labs/caddy-active-health/README.md) | 现有 Caddy 教程覆盖 TLS。补上探针如何驱动 HTTP 流量摘除和回切。 | Caddy 2.11.9，经过 SHA-512 校验。两次实测都得到主、备、主三个阶段各 5 次 HTTP 200。切换时主实例直连 `/inventory` 仍为 200，但 `/readyz` 为 503。 | [c6ae2c8](https://github.com/mengxiangsama/middleware-component-lab/commit/c6ae2c889c8379ec948a732994dd7786f297c8bb) |
| [Hazelcast IMap + Java](../labs/hazelcast-imap-backup/README.md) | Caffeine 教程已有各实例失效通知，补充共享分区数据的一次成员故障接管。 | Hazelcast 5.7.0 Community，三次实测。两个 TCP 成员各有独立端口，B 在 A 终止前有 1 个备份条目、0 个主条目，接手成为唯一成员及分区主成员后仍读到 `SHIPPED`。 | [5d6aa64](https://github.com/mengxiangsama/middleware-component-lab/commit/5d6aa6496c29801141ef250b96ef218e25e7805b) |
| [PostgreSQL 事务 Outbox + Debezium](../labs/debezium-postgres-outbox/README.md) | 普通 CDC 教程读取订单表行变更。发货服务还需要让业务写入与待发布的业务事件在同一个事务内生效。 | PostgreSQL 17.12、Debezium 3.2.7.Final。回滚订单与 Outbox 都是 0 行，提交后各 1 行。实际 Router 输出主题 `outbox.event.shipment`、键 `102`、事件 ID `evt-commit` 和对应 JSON。回调随后收到已提交的 `evt-barrier`，有序记录中没有回滚事件。验证到进程内 SourceRecord。 | [f5d6003](https://github.com/mengxiangsama/middleware-component-lab/commit/f5d6003167b9f1a3d7d01d8eaef2c36d40ee592d) |

本地为 macOS 26.5 ARM64，Java 实验使用 OpenJDK 17.0.16，Pulsar 本机 Broker 与客户端验证使用 OpenJDK 21.0.5，Python 使用 3.13.7。根项目 `./mvnw -B -ntp verify` 通过，11 项通过，原有 3 项需要 RocketMQ Broker 的测试按条件跳过。用官方本机 `promtool 3.16.0` 检查及执行既有告警规则，均成功。

## 2026-11-28 · Grafana k6 库存 API 发布门禁

从 `10b0d4f` 同步并盘点首页、组件目录、既有实验和最近提交。仓库已有 Toxiproxy 客户端超时、Caddy 就绪切换和 Prometheus 业务指标教程。gRPC 标准健康检查仍是可补方向，近期已做过就绪探针主题。优先选此前未收录的 Grafana k6，补齐候选 API 如何通过实际 HTTP 响应与业务断言产生可由发布流水线读取的结果。[官方阈值示例](https://grafana.com/docs/k6/latest/using-k6/thresholds/) · [2.4.0 上游仓库](https://github.com/grafana/k6/tree/v2.4.0)

在 macOS 26.5 ARM64 和 Python 3.13.7 上使用 Grafana k6 2.4.0。官方 macOS ARM64 包的 SHA-256 为 `5715745adf4e5ebc92965b26b7785e54e685a370466448cb587b383330a24f20`，下载后校验通过。带绝对路径执行 `python3 -B labs/k6-http-release-gate/verify.py --k6 /path/to/k6`，再把 k6 放进 `PATH` 复跑。两次都对照服务端记录和 k6 的 JSON 指标。稳定版 20 次 200，失败比例 0%，业务检查通过率 100%，k6 退出 0。候选版 16 次 200 与 4 次 503，失败比例 20%，业务检查通过率 80%，k6 退出 99。两版均完成 20 次迭代且没有丢弃。

再用临时脚本副本把执行上限设为 1 秒，后端在 1.5 秒后返回 HTTP 200。实测完成 2 次，丢弃 18 次，HTTP 失败比例为 0%，迭代阈值仍使 k6 退出 99。验证范围为本地库存接口、真实请求和阈值退出行为。首页、组件目录、发布手册和学习路线都补上了入口。[运行教程](../labs/k6-http-release-gate/README.md) · [提交](https://github.com/mengxiangsama/middleware-component-lab/commit/ac709a95297c0ac9660c6cb03ff721101777f381)

## 2026-11-28 · gRPC Java 标准健康检查

从 `9449bf0` 同步并检查首页、组件配置、已有实验和近期提交。现有 gRPC Java 实验覆盖截止时间传播，Actuator 和 Caddy 已覆盖 HTTP 就绪检查，k6 已覆盖发布门禁。优先补 gRPC 专用的健康检查，用标准 Health RPC 区分依赖未就绪与进程无法运行。[gRPC 健康协议](https://grpc.io/docs/guides/health-checking/) · [Java 1.77.0 官方示例](https://github.com/grpc/grpc-java/tree/v1.77.0/examples/src/main/java/io/grpc/examples/healthservice)

在 macOS 26.5 ARM64、OpenJDK 17.0.16 和 Python 3.13.7 上运行 `./mvnw -B -ntp -f labs/grpc-health-probe/pom.xml clean package`，接着运行 `python3 -B labs/grpc-health-probe/verify.py --probe /path/to/grpc_health_probe`。Java 使用 grpc-java 1.77.0，官方探针为 0.4.63，macOS ARM64 二进制经 SHA-256 校验，摘要为 `95b145830d2b4040c953dcf0c78772389d8b49ef7b7c5a1f74b130d051b3d1b5`。独立运行与复测均通过。就绪和未命名检查依次退出 `4 → 0 → 4 → 0`，存活始终退出 `0`。未知服务名返回 gRPC `NOT_FOUND`，探针退出 `3`。故意让探针返回与预期不同的状态，脚本报告失败并释放实验服务端口。[探针退出码源码](https://github.com/grpc-ecosystem/grpc-health-probe/blob/v0.4.63/main.go#L348-L376)

将教程中的探针片段放入 Deployment，用 kubeconform 0.8.0 和 Kubernetes 1.35.0 严格 Schema 校验通过。把数字端口换成字符串的对照被拒绝。实际 RPC 联调在本机完成，状态变化通过命令触发。Kubernetes 流量摘除和容器重启留给目标部署环境验证。首页、组件目录、网络编程与发布手册、学习路线均补上入口。[运行教程](../labs/grpc-health-probe/README.md) · [提交](https://github.com/mengxiangsama/middleware-component-lab/commit/23204f600564fcd46c605601d2d8623656ec34cc)
