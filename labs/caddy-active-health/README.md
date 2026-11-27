# Caddy 主动健康检查与回切

库存服务由主、备两个 HTTP 实例提供。首选实例短暂失去数据库连接、还在预热或即将下线时，可以通过 readiness 让 Caddy 暂时把请求转给备用实例。首选实例恢复就绪后，Caddy 再把请求派回首选实例。

## 接入步骤

1. 两个后端都实现 `GET /readyz`，业务依赖就绪时返回 200，暂时无法接流量时返回 503。业务接口独立。本例的 `GET /inventory` 返回处理请求的实例标识。
2. 在 [Caddyfile](Caddyfile) 的 `reverse_proxy` 中依次声明首选、备用上游。配置 `lb_policy first` 选择列表中的第一个可用实例。
3. 设置 `health_uri /readyz` 和 `health_status 200`，让 Caddy 定时直接探测两台上游。本地实验每秒探测一次，单次超时 500 毫秒。一次失败摘除，一次通过恢复，以便短时间内看到状态变化。接入实际服务时，按依赖响应时间和短时波动选择探测周期与连续成功、失败次数。

## 运行与验证

准备 Python 3.10+ 和 Caddy。从仓库根目录运行以下命令，替换成已安装的 Caddy 命令或二进制路径。本实验用 Caddy 2.11.9、Python 3.13.7 验证。

```bash
python3 -B labs/caddy-active-health/verify.py --caddy /absolute/path/to/caddy
```

[脚本](verify.py) 在环回地址启动两个 Python HTTP 后端和 Caddy，为它们分配临时端口。确认 Caddy 已探测两端后，脚本改变首选实例的 readiness 并持续请求代理入口。

| 阶段 | 首选实例直连 | 从 Caddy 请求 `/inventory` |
| --- | --- | --- |
| 主备就绪 | `/readyz` 200 | 连续 5 次 200，均由 `primary` 处理 |
| 首选暂时未就绪 | `/readyz` 503，`/inventory` 仍返回 200 | 等待探测生效后连续 5 次 200，均由 `standby` 处理 |
| 首选恢复 | `/readyz` 恢复为 200 | 等待探测生效后连续 5 次 200，均由 `primary` 处理 |

这里保留了首选实例业务接口的成功响应，用来验证切换由主动探测的结果驱动。脚本在每次切换后等待实际业务流量达到预期，而固定的探测间隔决定状态变化何时可见。退出时停止本轮实例。

## 官方资料

- [Caddy 主动健康检查](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#active-health-checks)
- [Caddy 负载均衡策略](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#load-balancing)
- [Caddy v2.11.9 官方发布](https://github.com/caddyserver/caddy/releases/tag/v2.11.9) · [该版本的健康检查实现](https://github.com/caddyserver/caddy/blob/v2.11.9/modules/caddyhttp/reverseproxy/healthchecks.go)
