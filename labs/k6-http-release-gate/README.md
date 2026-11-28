# Grafana k6 + HTTP 库存 API 发布门禁

候选版本偶尔查不到库存时，发布流水线需要用实际请求判断能否继续。k6 是可用 JavaScript 编写场景的测试工具。它发送 HTTP 请求并采集结果，按预设阈值给出进程退出码。库存 API 负责查询测试商品，流水线根据 k6 的退出状态决定是否进入下一步。

## 接入步骤

1. 在测试环境准备库存 API 和一组有库存的固定商品。实验使用 `sku-0` 到 `sku-19`，接口为 `GET /inventory/{sku}`。顺利读取时返回 HTTP 200 和 JSON `{"available":true}`。
2. 在 [k6 脚本](release-gate.js) 中配置两个虚拟用户共享 20 次迭代，用 `iterationInTest` 为每次请求选择商品。将 HTTP 200 设为预期状态，由 `http_req_failed` 统计请求失败比例。用 `check()` 同时核对实际状态码和 JSON。
3. 在 `thresholds` 中要求 HTTP 失败比例严格小于 5%，业务检查通过比例严格大于 95%，完成 20 次迭代，且丢弃数为 0。到达时间上限时，迭代阈值会拦截样本不足的结果。流水线直接运行 k6，保留退出码。k6 2.4.0 在门槛通过时退出 0，阈值失败时退出 99。

候选 API 启动后，从仓库根目录运行以下命令。将地址换成候选服务的测试地址，并按实际接口调整商品路径和响应检查。

```sh
INVENTORY_URL=http://127.0.0.1:8080 k6 run --no-usage-report labs/k6-http-release-gate/release-gate.js
```

## 本地验证

准备 Python 3.9+ 和 [Grafana k6 2.4.0](https://github.com/grafana/k6/releases/tag/v2.4.0)。下载与本机系统和架构匹配的二进制并校验 SHA-256。从仓库根目录运行以下命令。已将可执行文件加入 `PATH` 时可省略 `--k6`。

```sh
python3 -B labs/k6-http-release-gate/verify.py --k6 /path/to/k6
```

[验证脚本](verify.py)分别启动两个临时的本地 HTTP 服务。稳定版本对 20 次查询都返回 200。候选版本每五个商品返回一次 503。脚本对照记录在服务端的商品及状态码、k6 JSON 指标和两个进程退出码。两轮对照都符合预期时，验证脚本退出 0。

| 版本 | 实际 HTTP 响应 | `http_req_failed` | 业务检查通过率 | k6 退出码 |
| --- | --- | ---: | ---: | ---: |
| 稳定版 | 20 次 200 | 0% | 100% | 0 |
| 候选版 | 16 次 200，4 次 503 | 20% | 80% | 99 |

2026-11-28 在 macOS 26.5 ARM64、Python 3.13.7 和通过 SHA-256 校验的 k6 2.4.0 上实测。两种版本均完成 20 次迭代，服务端每个商品只收到一次请求，丢弃迭代数为 0。验证范围为固定请求规模下的 HTTP 和业务阈值判定。拿它做性能基线时，需要重新选择数据、负载模型和时间窗口。

另用临时副本把场景上限设为 1 秒，让接口在 1.5 秒后返回正确的 200。k6 实际完成 2 次、丢弃 18 次。HTTP 失败比例为 0%，迭代数阈值仍触发退出码 99。

## 官方资料

- [k6 阈值与退出行为](https://grafana.com/docs/k6/latest/using-k6/thresholds/) · [检查与阈值](https://grafana.com/docs/k6/latest/using-k6/checks/)
- [共享迭代场景](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/shared-iterations/) · [迭代索引](https://grafana.com/docs/k6/latest/javascript-api/k6-execution/)
- [HTTP 响应分类](https://grafana.com/docs/k6/latest/javascript-api/k6-http/set-response-callback/) · [JSON 指标格式](https://grafana.com/docs/k6/latest/results-output/real-time/json/)
- [k6 v2.4.0 官方仓库](https://github.com/grafana/k6/tree/v2.4.0) · [该版本的退出码定义](https://github.com/grafana/k6/blob/v2.4.0/errext/exitcodes/codes.go)
