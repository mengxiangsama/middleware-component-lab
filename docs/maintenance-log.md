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
