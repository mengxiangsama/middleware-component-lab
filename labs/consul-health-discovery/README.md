# Consul + HTTP 健康检查实现门店查询服务发现

## 适用场景

门店商品查询运行两个实例。调用方先向 Consul 查找健康实例，再把请求发给查询结果里的地址。某个实例的健康端点开始报错时，调用方刷新列表就能暂时跳过该实例。恢复后，实例可以重新进入候选集合。

| 组件 | 分工 |
| --- | --- |
| [Consul](../../docs/components/service-tools.md#consul) | 接收实例注册，定期访问 HTTP 健康端点，提供按状态筛选的服务列表。 |
| 两个 Python HTTP 实例 | 各自提供 `/healthz` 和 `/catalog`，模拟独立的实例地址和健康状态。 |
| 查询调用方 | 通过 Consul 获取健康实例，向返回地址发起商品查询。 |

## 接入步骤

1. 每个实例向所在的 Consul Agent 发送 `PUT /v1/agent/service/register`。填写公共服务名 `storefront`、不同的实例 ID、调用方可访问的 `Address` 和 `Port`。
2. 在注册内容的 `Check` 部分设置健康端点。实验请求 `GET /healthz`，间隔 1 秒、超时 500 毫秒。两个实例各自监听一个回环地址端口。
3. 调用方查询 `GET /v1/health/service/storefront?passing=true`，从结果的 `Service` 字段读取地址和端口。这个筛选只返回相关检查全部通过的实例。不加筛选参数的同一路径可用于排查已登记实例的检查状态。
4. 从健康实例集合选址后调用业务接口。实验逐个请求发现结果中的 `/catalog`，并核对响应中的实例 ID。

## 本地验证

需要 Python 3.9+ 和 [Consul 2.1.0](https://releases.hashicorp.com/consul/2.1.0/) 的 `consul` 可执行文件。下载并解压对应平台的官方包，Apple Silicon 对应 `consul_2.1.0_darwin_arm64.zip`。从仓库根目录运行。

```sh
python3 labs/consul-health-discovery/verify.py --consul /absolute/path/to/consul
```

脚本在回环地址启动临时 Consul 开发 Agent 和两个 HTTP 实例，退出时清理进程和临时目录。

| 阶段 | 不筛选的健康查询 | `passing=true` 的发现和实际调用 |
| --- | --- | --- |
| 初始 | A、B 都是 `passing` | A、B 都可发现，调用分别返回各自 ID |
| B 的 `/healthz` 返回 503 | B 仍有注册记录，检查变为 `critical` | 只发现和调用 A |
| B 的 `/healthz` 恢复 200 | A、B 都是 `passing` | A、B 重新进入发现和调用结果 |

实验验证单 Agent 中的 HTTP 健康状态和服务发现。生产部署按拓扑另行验证多节点、ACL 和 TLS。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、Python 3.13.7、Consul 2.1.0 上运行上述命令。初始和恢复时发现列表均为 A、B，故障期间 B 为 `critical` 且只发现 A。每一阶段对发现地址发出的 HTTP 请求都返回了预期实例 ID。ARM64 压缩包已核对官方 SHA-256。

## 官方资料

- [Consul 2.1.0 发布](https://github.com/hashicorp/consul/releases/tag/v2.1.0)
- [注册服务 API 和示例](https://developer.hashicorp.com/consul/api-docs/agent/service#register-service) · [HTTP 健康检查配置](https://developer.hashicorp.com/consul/docs/reference/service/health-check)
- [按服务查健康节点](https://developer.hashicorp.com/consul/api-docs/health#list-nodes-for-service) · [Agent 端口](https://developer.hashicorp.com/consul/docs/reference/architecture/ports)
