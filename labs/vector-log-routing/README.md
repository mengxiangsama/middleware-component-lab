# Vector + HTTP 日志接收端按字段转发

## 适用场景

发货服务输出逐行 JSON。运维接收端只需要订单号和事件类型，发送前用 Vector 解析记录，构造只包含需要字段的新事件。格式失败或缺少合规字段的记录转到单独的诊断输出，供排查采集问题。

## 接入步骤

1. 把服务日志接到 Vector source。本例用 [stdin](vector.toml)，实际接在日志文件或容器输出时替换 source，保持逐行事件边界。
2. 用 `remap` 对每条记录执行 `parse_json`，并检查订单号和事件字段是字符串。通过时按允许字段重建事件；失败时写入固定的原因，不输出原始记录。配置 `drop_on_error`，让未处理的转换错误也不会流向下游。
3. 用 `route` 将合格事件转给 HTTP sink，并将已知解析失败和字段错误转给独立输出。将 HTTP URI 配到可信的接收端，本实验用本机 Python 接收器并响应 204。

## 验证方法

准备 Python 3 和适合系统架构的 [Vector 0.62.0](https://github.com/vectordotdev/vector/releases/tag/v0.62.0) 官方发行包，校验后解压。在仓库根目录运行。

```sh
python3 labs/vector-log-routing/verify.py --vector /absolute/path/to/vector
```

脚本启动回环 HTTP 服务，将三行数据送给 Vector。HTTP 只收到订单 `O-101` 的 `SHIPPED` 记录和分类字段。输入中的 `email` 和 `authorization` 不会被转发。错误 JSON 和类型不符的订单号分别输出 `malformed_json` 与 `invalid_fields`，两条诊断都只含分类和原因。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、Python 3.13.7 和经 SHA-256 校验的 Vector 0.62.0 上运行。真实发生 1 次 HTTP POST 和 2 条拒绝诊断。验证范围为本机字段筛选和路由，生产接收端认证、重试、吞吐和落盘需在使用环境另测。

## 官方资料

- [Vector 官方仓库](https://github.com/vectordotdev/vector) · [Remap 和 VRL 示例](https://vector.dev/docs/reference/configuration/transforms/remap/)
- [Route](https://vector.dev/docs/reference/configuration/transforms/route/) · [HTTP sink](https://vector.dev/docs/reference/configuration/sinks/http/)
