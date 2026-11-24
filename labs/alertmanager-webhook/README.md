# Prometheus + Alertmanager 合并订单服务告警

## 适用场景

两个订单实例的指标同时抓取失败时，值班人员需要收到一条包含两个实例的通知，方便定位影响范围。实例恢复后，再用恢复通知结束这次告警。

| 组件 | 分工 |
| --- | --- |
| [Prometheus](../../docs/components/service-tools.md#prometheus) | 抓取指标、求值告警规则，并把触发和恢复状态发送给 Alertmanager。 |
| [Alertmanager](../../docs/components/observability-tools.md#alertmanager) | 按告警名和任务名分组，向 Webhook 投递通知。 |
| Webhook 接收端 | 解析 HTTP POST 的 JSON 正文，读取故障或恢复的实例列表。 |

## 接入步骤

1. 将订单实例的指标地址加入 Prometheus 的 `orders` job。[prometheus.yml](prometheus.yml) 每秒抓取两个本地测试实例的 `/metrics`，也每秒求值一次。配置通过 `rule_files` 加载 [alerts.yml](alerts.yml)。同一实例持续满足 `up{job="orders"} == 0` 满 3 秒后，规则触发 `OrdersMetricsUnreachable`。
2. 在 `alerting.alertmanagers` 中填写 Alertmanager 地址。本实验使用 `127.0.0.1:19093`，Prometheus 将每个实例的告警连同 `alertname`、`job`、`instance` 标签发送过去。
3. 在 [alertmanager.yml](alertmanager.yml) 中将路由指向 `orders-webhook`。用 `group_by: [alertname, job]` 让同一 job 的同名告警归入一组，保留每条告警的 `instance` 标签。新组等待 `group_wait: 6s`，等待期间进入同一组的告警可以合并到首条通知。发出首条通知后，按 `group_interval: 4s` 检查组内变更。持续故障的重复提醒间隔为 1 小时。[官方路由配置](https://prometheus.io/docs/alerting/latest/configuration/#route)
4. 在接收方的 `webhook_configs` 中配置 `http://127.0.0.1:18083/webhook` 和 `send_resolved: true`。接收端处理 Alertmanager 的 JSON POST，通过 `status` 区分触发和恢复，通过 `alerts[].labels.instance` 获取实例地址。[官方 Webhook 格式](https://prometheus.io/docs/alerting/latest/configuration/#webhook_config)

## 验证方法

准备 Python 3.9+，将对应系统和架构的 Prometheus 3.16.0 与 Alertmanager 0.35.0 原生程序加入 `PATH`。从仓库根目录运行命令。实验使用本机回环端口 `18081`、`18082`、`18083`、`19090` 和 `19093`。

```sh
python3 labs/alertmanager-webhook/verify.py
```

脚本启动两个模拟订单指标端和一个本地 Webhook，再启动真实的 Prometheus 和 Alertmanager。两个指标端先返回 HTTP 200，随后一起切到 HTTP 503。收到触发通知后，脚本将两个指标端切回 HTTP 200。

| 阶段 | Webhook 端应收到 |
| --- | --- |
| 两个实例抓取失败并持续到触发 | 一条 `firing`，`alerts` 数组包含 `127.0.0.1:18081` 与 `127.0.0.1:18082` |
| 两个实例恢复抓取 | 一条 `resolved`，`alerts` 数组包含同样两个实例 |

脚本还核对两条通知的 `groupKey` 相同，并再观察 5 秒，覆盖下一次分组检查。

秒级抓取、规则等待和通知分组间隔用于缩短演练。接入实际服务时，按可接受的通知延迟和需要合并的时间范围设置参数。实验覆盖单个 Alertmanager 向本地 HTTP Webhook 投递，指标和通知接收端由 Python 模拟。值班平台接收、静默和高可用可按实际部署另行验证。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、Python 3.13.7 上，使用官方原生发行版 Prometheus 3.16.0 和 Alertmanager 0.35.0 运行实验。接收端各收到一条 `firing` 和 `resolved`，两条通知的 `groupKey` 相同，且都包含两个实例。下一次分组检查期间未收到额外通知。

从仓库根目录检查两份配置时，`promtool check config labs/alertmanager-webhook/prometheus.yml` 和 `amtool check-config labs/alertmanager-webhook/alertmanager.yml` 均通过。

## 官方资料

- [Prometheus 3.16.0 发布](https://github.com/prometheus/prometheus/releases/tag/v3.16.0) · [告警规则](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/) · [配置 Alertmanager 接收地址](https://prometheus.io/docs/prometheus/latest/configuration/configuration/#alertmanager_config)
- [Alertmanager 0.35.0 发布](https://github.com/prometheus/alertmanager/releases/tag/v0.35.0) · [官方路由示例](https://github.com/prometheus/alertmanager/blob/v0.35.0/doc/examples/simple.yml) · [分组机制](https://prometheus.io/docs/alerting/latest/alertmanager/#grouping)
