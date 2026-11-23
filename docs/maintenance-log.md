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
