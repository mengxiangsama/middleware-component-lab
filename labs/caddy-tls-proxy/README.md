# Caddy + Python HTTP 接口的本地 HTTPS 反代

## 适用场景

开发环境的商品查询接口只监听本机 HTTP 端口，调用方要通过 HTTPS 访问。Caddy 接收 TLS 连接，把匹配的商品查询转给 Python 后端，并传递原始地址和协议供后端生成链接或记录日志。

| 组件 | 分工 |
| --- | --- |
| Caddy | 使用自己的临时 CA 签发本地证书，为前端连接终止 TLS，并匹配 `/catalog/*` 路由。 |
| Python HTTP 服务 | 在回环地址接收转发，返回商品查询结果及收到的请求上下文。 |
| Python HTTPS 客户端 | 通过专用验证上下文加载这次实验的根证书，校验连接和业务响应。 |

## 接入步骤

1. 指定 HTTPS 入口的地址，在站点启用 `tls internal`。全局配置使用 `skip_install_trust`，由本次测试客户端单独加载临时 `root.crt`，系统信任库保持原样。数据目录由脚本创建，Caddy 在目录内管理 CA 私钥。
2. 匹配 `/catalog/*` 后，用 `reverse_proxy` 将请求交给回环地址上的 HTTP 服务。未匹配的请求由默认 `handle` 返回 404。
3. 后端读取 `Host`、`X-Forwarded-Host` 和 `X-Forwarded-Proto`。实验核对原始 Host、路径、查询参数和 `https` 协议值。

匹配规则、TLS 设置和回环地址见 [Caddyfile](Caddyfile)。

## 本地验证

需要 Python 3.9+ 和 [Caddy 2.11.9](https://github.com/caddyserver/caddy/releases/tag/v2.11.9)。下载对应平台的官方包并解压，Apple Silicon 对应 `caddy_2.11.9_mac_arm64.tar.gz`。从仓库根目录运行。

```sh
python3 labs/caddy-tls-proxy/verify.py --caddy /absolute/path/to/caddy
```

脚本启动一个 Caddy 进程和一个 Python 商品查询服务，给它们分配回环端口。退出时回收进程、临时证书和配置文件。

| 请求 | 核对结果 |
| --- | --- |
| 未加载临时 CA 的 HTTPS 客户端 | 校验证书失败 |
| 加载临时 `root.crt` 后查询 `/catalog/items?sku=K-17` | 返回商品 K-17、可用量 3。后端收到完整路径、查询参数和原始 Host，`X-Forwarded-Proto` 为 `https`。 |
| 同一 HTTPS 入口查询 `/other` | 返回 404，后端收到的请求数不变 |

实验覆盖回环地址上的 HTTP/1.1、临时内部 CA 和路径反代。公网证书、浏览器信任配置和跨主机回源可在部署环境单独验证。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、Python 3.13.7 和 Caddy 2.11.9 上执行上述脚本，三组检查均通过。实测包已核对官方发布文件中的 SHA-512。

## 官方资料

- [本地 HTTPS](https://caddyserver.com/docs/automatic-https#local-https) · [内部签发](https://caddyserver.com/docs/caddyfile/directives/tls) · [不安装到系统信任库](https://caddyserver.com/docs/caddyfile/options#skip-install-trust)
- [反向代理快速入门](https://caddyserver.com/docs/quick-starts/reverse-proxy) · [默认请求头和转发规则](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#headers)
