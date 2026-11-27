# Open Policy Agent + Python 按仓库授权查运单

仓库操作员查询运单时，网关读取操作员和运单各自的仓库，再让 OPA 判断这次读取是否允许。规则适用于已有身份认证和业务数据来源的服务，需要按角色、资源归属和操作组合做授权。OpenFeature / flagd 管理功能发布开关，OPA 在这里负责单次请求的资源访问决策。

## 接入步骤

1. 用 Rego v1 表达默认拒绝。只有 `shipment.read`、角色为 `warehouse_clerk` 且员工与运单的仓库相同时返回 `true`。规则在 [shipment.rego](shipment.rego)。
2. 网关根据请求凭证查询身份，再按请求里的运单号查询资源。本例使用 [gateway.py](gateway.py) 中的固定测试数据模拟两个查询，其中的凭证仅用于选择本地样例身份。请求头 `X-Role` 和 `X-Warehouse` 不参与决策。接入业务时应从已认证的身份和服务端业务数据中取得这些属性。
3. 网关向 OPA 发送 `POST /v1/data/shipment/authorization/allow`，请求体用 `input` 包装操作名、员工角色和归属、运单归属。OPA 返回 `{"result":true}` 后才发送运单数据。策略明确拒绝时返回 403，决策不可用时返回 503，响应只含错误信息。

## 运行测试

使用 Python 3.9+ 和 [OPA CLI](https://www.openpolicyagent.org/docs#1-download-opa)。本实验用 OPA 1.23.0、Python 3.13.7 验证。将 `OPA_BIN` 设成已安装的 `opa` 命令或二进制绝对路径，从仓库根目录运行。

```bash
LAB=labs/opa-shipment-authorization
OPA_BIN=opa
"$OPA_BIN" check --strict "$LAB"
"$OPA_BIN" test -v "$LAB"
OPA_BIN="$OPA_BIN" python3 -B -m unittest discover -s "$LAB" -v
```

Rego 测试直接验证默认拒绝与不同输入的决策。HTTP 集成测试在本机启动 OPA 和 Python 网关，通过网关实际访问策略 API，核对以下结果。

| 请求 | 预期响应 |
| --- | --- |
| 操作员查询本仓库运单 | 200，返回运单 ID 和仓库 |
| 操作员查询其他仓库运单 | 403，即使请求头声称属于该仓库 |
| 访客查询本仓库运单 | 403，即使请求头声称具有操作员角色 |
| 请求未提供样例凭证 | 401 |
| 本仓库操作员查询运单时 OPA 地址无监听服务 | 503，响应不含运单信息 |

## 查看 OPA 的 HTTP 决策

在一个终端用本地地址启动策略服务。

```bash
opa run --server --addr 127.0.0.1:8181 labs/opa-shipment-authorization/shipment.rego
```

在另一个终端提交一笔跨仓库决策，应看到 `{"result":false}`。把业务输入里的运单仓库改为 `WH-NORTH` 时应看到 `{"result":true}`。这里直接提交 `input` 便于检查策略，业务调用走网关生成输入。

```bash
curl -fsS http://127.0.0.1:8181/v1/data/shipment/authorization/allow \
  -H 'Content-Type: application/json' \
  -d '{"input":{"action":"shipment.read","principal":{"role":"warehouse_clerk","warehouse":"WH-NORTH"},"shipment":{"warehouse":"WH-SOUTH"}}}'
```

如需手动调用网关，从第三个终端运行 `python3 -B labs/opa-shipment-authorization/gateway.py`，再用下面的请求查询本仓库运单。用 Ctrl+C 停止两个服务。

```bash
curl -i -H 'Authorization: Bearer lab-clerk-north' http://127.0.0.1:8095/shipments/S-1001
```

## 官方资料

- [OPA v1.23.0 官方仓库和二进制发布](https://github.com/open-policy-agent/opa/releases/tag/v1.23.0)
- [HTTP API 授权官方示例](https://www.openpolicyagent.org/docs/http-api-authorization)
- [Data API POST 请求与结果](https://www.openpolicyagent.org/docs/rest-api#get-a-document-with-input)
- [Rego 默认规则](https://www.openpolicyagent.org/docs/policy-language#default-keyword) · [策略测试](https://www.openpolicyagent.org/docs/policy-testing)
