# Netty WebSocket 回显实验

聊天、实时看板和在线协作需要服务端主动推送，客户端也可以随时发送消息。WebSocket 在一次 HTTP Upgrade 握手后保持双向连接。Netty 是 Java 异步网络框架，可以在 Channel Pipeline 中分别处理 HTTP 编解码、WebSocket 握手和应用消息。

实验在 `ws://127.0.0.1:8091/ws` 接收 UTF-8 文本，并在同一连接中原样返回。Netty 处理 Ping、Pong 和关闭帧。应用会合并分片文本，单条消息上限为 64 KiB，收到二进制消息时以状态码 `1003` 关闭连接。服务只监听本机，使用明文 `ws://`。

## 依赖与启动

- 使用 JDK 17 或更高版本，在仓库根目录运行 Maven Wrapper。
- [POM](pom.xml) 声明 `io.netty:netty-codec-http:4.2.21.Final`，其传递依赖提供 Netty 的 NIO 传输和其余编解码组件。构建会生成可直接运行的 JAR。
- 下面的命令行客户端使用 Node.js 22.4 或更高版本的内置 `WebSocket`，无需安装 npm 包。

```bash
./mvnw -f labs/netty-websocket/pom.xml clean verify
java -jar labs/netty-websocket/target/netty-websocket-lab-1.0.0-SNAPSHOT.jar
```

看到 `Listening on ws://127.0.0.1:8091/ws` 后，保持这个终端运行。端口已被占用时，可在 JAR 路径后传入其他端口，并同步修改客户端的连接地址。

## 连接并验证

在另一个终端执行以下命令。客户端在握手完成后发送一条文本，收到同样的文本后主动用状态码 `1000` 关闭。命令会检查回显内容和关闭结果，遇到连接错误或超时会以非零状态退出。

```bash
node --input-type=module <<'JS'
import assert from 'node:assert/strict';

const text = 'Netty 你好';
const socket = new WebSocket('ws://127.0.0.1:8091/ws');
let receivedEcho = false;
const timeout = setTimeout(() => {
  console.error('WebSocket verification timed out');
  process.exit(1);
}, 5000);

socket.addEventListener('open', () => {
  console.log('Handshake OK');
  socket.send(text);
}, { once: true });

socket.addEventListener('message', ({ data }) => {
  assert.equal(data, text);
  receivedEcho = true;
  console.log('Echo:', data);
  socket.close(1000, 'done');
}, { once: true });

socket.addEventListener('close', ({ code, wasClean }) => {
  clearTimeout(timeout);
  assert.equal(receivedEcho, true);
  assert.equal(code, 1000);
  assert.equal(wasClean, true);
  console.log('Close 1000 (clean)');
}, { once: true });

socket.addEventListener('error', () => {
  throw new Error('WebSocket connection failed');
}, { once: true });
JS
```

2026-11-23 在 macOS arm64、OpenJDK 17.0.16 和 Node.js 22.16.0 上，构建成功，按上述步骤启动服务后，客户端退出码为 0，实际输出如下。

```text
Handshake OK
Echo: Netty 你好
Close 1000 (clean)
```

同次验证中，错误路径返回 HTTP `404`，二进制消息收到关闭码 `1003`，超出 64 KiB 的文本收到关闭码 `1009`。验证结束后，在服务端终端按 `Ctrl+C` 停止监听。

构建中的 [JUnit 测试](src/test/java/io/github/nettylab/WebSocketEchoServerTest.java) 还会用 JDK 自带客户端建立真实连接，发送分片的中文文本，检查回显和正常关闭。

## 服务端怎样处理消息

1. `HttpServerCodec` 和 `HttpObjectAggregator` 解码并聚合 HTTP 握手请求。
2. `WebSocketServerProtocolHandler` 将 `/ws` 请求升级为 WebSocket，并处理控制帧。
3. `WebSocketFrameAggregator` 合并同一消息的分片，[回显处理器](src/main/java/io/github/nettylab/WebSocketEchoServer.java) 把完整文本写回当前连接。

## 官方资料

- [Netty 4.2.21.Final 发布记录](https://netty.io/news/2026/11/16/4-2-21-Final.html)
- [Netty WebSocketServerProtocolHandler API](https://netty.io/4.2/api/io/netty/handler/codec/http/websocketx/WebSocketServerProtocolHandler.html)
- [RFC 6455 WebSocket 协议](https://www.rfc-editor.org/rfc/rfc6455)
- [Node.js 内置 WebSocket API](https://nodejs.org/download/release/v22.15.0/docs/api/globals.html#websocket)
