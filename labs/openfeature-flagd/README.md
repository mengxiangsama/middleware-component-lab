# OpenFeature + flagd 按门店启用新运费预估

## 适用场景

同一个结算服务同时保留稳定版与新版运费预估，先向指定门店启用新版。Go 应用使用 OpenFeature 的布尔型 API，flagd 存放规则并通过 gRPC 求值。应用将订单对应的门店 ID 放进本次求值上下文。

## 接入步骤

1. 运行 flagd，使用 `start --uri file:/data/flags.json` 读入规则。[本例规则](flags.json)定义 `pilot=true` 和 `stable=false` 两个变体。门店 `store-sh-01` 匹配试点，其他门店使用规则的默认变体 `stable`。
2. 在应用使用 [固定模块版本](go.mod)，创建 flagd RPC provider，配置 host、port 与调用期限，通过 `SetProviderAndWait` 完成初始化。
3. 将业务订单中的 `storeId` 放在 `EvaluationContext`，调用 `BooleanValueDetails`。依据返回的布尔值选择计算方式，同时记录变体、求值原因和错误。应用传入的默认值 `false` 用于旗标不存在或其他求值失败的情形，规则的默认变体用于已找到旗标但未命中定向规则的情形。

## 验证方法

准备 Go 1.25+、Docker 和可下载 Go 依赖及镜像的网络。在仓库根目录运行。

```sh
sh labs/openfeature-flagd/verify.sh
```

脚本创建临时 flagd 容器，只把 gRPC 端口发布到回环地址，结束时关闭容器。程序对同一个客户传入两个门店上下文，再请求不存在的旗标。

| 请求 | 预期结果 |
| --- | --- |
| 门店 `store-sh-01` | `true` · `pilot` · `TARGETING_MATCH` |
| 门店 `store-hz-01` | `false` · `stable` · `DEFAULT` |
| 不存在的旗标 | `false` · `FLAG_NOT_FOUND` |

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、Go 1.25.0 和 Docker Engine 27.3.1 上运行。OpenFeature Go SDK 1.17.0 通过 flagd provider 0.6.0 访问真实 flagd 0.19.0 三次，结果和预期一致。验证范围为单节点、静态规则文件和远程求值。规则重载、跨副本同步时延及容灾需在目标环境验证。

## 官方资料

- [flagd 0.19.0](https://github.com/open-feature/flagd/releases/tag/flagd%2Fv0.19.0) · [规则与定向示例](https://flagd.dev/reference/flag-definitions/)
- [OpenFeature Go SDK](https://openfeature.dev/docs/reference/sdks/server/go/) · [Go RPC provider 用法](https://flagd.dev/providers/go/) · [provider 0.6.0 源码](https://github.com/open-feature/go-sdk-contrib/tree/providers/flagd/v0.6.0/providers/flagd)
