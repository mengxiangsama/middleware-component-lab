# NGINX 代理 SSE 订单进度

## 适用场景

下单页通过一条 HTTP 连接接收打包、发货等订单状态。上游生成并刷新 SSE 事件，NGINX 将请求转给上游并将响应流交给客户端。若代理积攒响应，页面可能等到缓冲区刷新或连接结束才收到已经生成的状态。

## 接入步骤

1. 上游用 `Content-Type: text/event-stream` 返回 UTF-8 响应。每条事件以空行结束，并在生成后刷新输出。本实验的 Python 上游发送 `packed` 后保持连接打开，收到本地测试的放行信号后才发送 `shipped`。
2. 给 SSE 路径配置独立的 NGINX `location`，将 `proxy_pass` 替换为实际的上游地址。`proxy_buffering off` 让 NGINX 随读取转递响应，`proxy_cache off` 关闭此路径上的代理缓存。以下设置来自 [实验配置](nginx.conf.in)。

```nginx
location = /orders/42/events {
    proxy_pass http://127.0.0.1:8081;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
}
```

这里显式使用 HTTP/1.1 连接上游，并清除代理默认发送的 `Connection` 头。上游还返回了 `Cache-Control: no-cache`。

3. 通过代理访问该路径，在上游继续生成下一条事件前检查客户端是否已读到前一条。线上长连接应按事件节奏配置 `proxy_read_timeout`。它限制相邻两次上游读取之间的等待时间，间隔较长时可由应用发送 SSE 心跳注释。[响应缓冲](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_buffering) · [读取超时](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_read_timeout)

## 本地验证

需要 Python 3.9+ 和 NGINX。将 `nginx` 加入 `PATH`，或在仓库根目录运行时明确指定二进制路径。

```sh
python3 labs/nginx-sse/verify.py --nginx /absolute/path/to/nginx
```

可从 [NGINX 1.30.8 官方源码](https://nginx.org/download/nginx-1.30.8.tar.gz) 构建本实验所需的 HTTP 代理。解压后在源码目录运行以下命令，得到 `objs/nginx`。

```sh
./configure --without-http_rewrite_module --without-http_gzip_module
make -j2
```

脚本在临时目录启动 NGINX 和 Python 上游，并只监听回环地址。客户端通过 NGINX 发出请求，核对响应类型是 `text/event-stream`，读取完整的 `packed` 事件后才放行上游，再读取 `shipped` 并确认响应结束。

2026-11-24 在 macOS 26.5 ARM64、Python 3.13.7 和原生构建的 NGINX 1.30.8 上实测通过。该验证覆盖单层代理以及两条逐步生成的 SSE 事件。浏览器重连和多级代理的超时配置需在实际部署环境验证。

## 官方资料

- [NGINX 反向代理示例](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#example) · [NGINX 源码项目](https://github.com/nginx/nginx/releases/tag/release-1.30.8)
- [SSE 事件流格式](https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream) · [代理组件索引](../../docs/components/traffic-routing.md#nginx)
