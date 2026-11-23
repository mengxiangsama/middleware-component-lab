# Prometheus 订单服务不可达告警实验

## 适用场景

上线告警前，用固定的指标样本检查等待时间、触发时刻、恢复和标签，避免一次短暂抓取失败就报警，或修改规则后无法发现行为变化。本实验使用 Prometheus **3.16.0** 镜像内的真实 `promtool` 规则求值器；输入是测试样本，无需启动订单应用或等待真实的几分钟。

规则见 [alerts.yml](alerts.yml)：当 `up{job="orders"} == 0` 持续满足 `for: 2m`，触发 `OrdersTargetDown`。每个实例按自己的标签分别累计等待时间。`severity: page` 随告警输出，`summary` 引用实例标签，便于后续接入通知路由。

## 接入步骤

需要可运行 Linux 容器的 Docker，以及 Bash、Zsh 或 POSIX Shell。首次运行需要联网拉取 `prom/prometheus:v3.16.0`。从仓库根目录执行：

```bash
sh labs/prometheus-rules/verify.sh
```

脚本依次输出版本、执行 `promtool check rules alerts.yml` 和 `promtool test rules alerts.test.yml`。实验目录以只读方式挂载，容器禁用网络且退出后自动删除，不占用服务端口。规则文件能通过语法检查，并不代表其触发行为符合预期，必须同时检查规则测试的结果。

接入已有 Prometheus 时，将规则文件放入配置目录，用 `rule_files` 加载，并确保抓取配置的 job 名称为 `orders`。以下片段需合并到自己的 `prometheus.yml`，规则路径相对于该配置文件：

```yaml
global:
  evaluation_interval: 1m
rule_files:
  - alerts.yml
```

本实验将采样间隔和求值间隔均设为 1 分钟，便于读时间线；实际告警延迟还受抓取和规则求值间隔影响。真实目标的抓取配置、配置检查和重新加载应按部署环境验证。

## 验证方法

[alerts.test.yml](alerts.test.yml) 中每组样本的第一项对应 `0m`，后续每项增加 1 分钟。例如 `1 0 0 0 1` 表示 `1m` 首次抓取失败，`3m` 才累计满两分钟，`4m` 恢复。

| 测试场景 | 样本 | 预期结果 |
| --- | --- | --- |
| 短暂故障 | `1 0 1 1 1` | 等待期间恢复，不产生 firing 告警 |
| 持续故障及恢复 | `1 0 0 0 1` | `2m` 为 pending，`3m` 为 firing，`4m` 解除 |
| 等待被恢复重置 | `1 0 1 0 0 0 1` | `2m` 恢复，`3m` 重新等待，`4m` 未触发，`5m` 才触发，`6m` 解除 |

持续故障场景还提供始终健康的第二个订单实例，以及另一个 job 的故障样本。断言只允许故障的订单实例触发，并核对标签和模板生成的注释，防止选择器或通知内容被误改。

`alert_rule_test` 检查 firing 告警；其期望为空不能证明存在 pending。实验另用 `promql_expr_test` 查询 `ALERTS{alertname="OrdersTargetDown"}`，断言结果包含 `alertstate="pending"`，并检查后续 firing 和恢复后的空结果。命令退出码应为 0，输出包含 `SUCCESS: 1 rules found` 和规则测试的 `SUCCESS`。

也可以将规则复制到临时目录，把 `for: 2m` 改成 `for: 1m` 再运行原测试。预期测试以非零退出，提前触发与原期望冲突；不要将这个故意改错的版本用于部署。

## 运行边界

`for` 要求同一标签集合在每次求值时持续满足条件，中途恢复会重新计时。本规则未设置 `keep_firing_for`，条件首次不满足时就解除。`up == 0` 检测的是已知抓取目标的失败，不能覆盖从服务发现中消失的目标；指标缺失需另设计规则。输入中的 `_` 和 `stale` 也不能当作 `up=0`。

这里验证的是合成样本上的规则行为。真实抓取、服务发现、Prometheus 到 Alertmanager 的连接及通知送达仍需单独联调。通知分组、抑制与静默见 [Alertmanager 手册](../../docs/components/observability-tools.md#alertmanager)。

## 官方资料

- [Prometheus 官方仓库](https://github.com/prometheus/prometheus)与 [3.16.0 发布记录](https://github.com/prometheus/prometheus/releases/tag/v3.16.0)。
- [官方规则测试样例](https://github.com/prometheus/prometheus/blob/v3.16.0/cmd/promtool/testdata/unittest.yml)及[配套规则](https://github.com/prometheus/prometheus/blob/v3.16.0/cmd/promtool/testdata/rules.yml)，可对照 `ALERTS` 的 pending 检查。
- [规则单元测试格式](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/)与 [`promtool` 命令](https://prometheus.io/docs/prometheus/latest/command-line/promtool/)。
- [告警规则、等待和恢复语义](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/)、[配置与规则加载](https://prometheus.io/docs/prometheus/latest/configuration/configuration/)、[官方 Docker 镜像用法](https://prometheus.io/docs/prometheus/latest/installation/)。
