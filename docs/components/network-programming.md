# 网络编程

## Netty

[WebSocket 实验 POM](../../labs/netty-websocket/pom.xml) 固定使用 `io.netty:netty-codec-http:4.2.21.Final`，其传递依赖提供同版本的 NIO 传输和 `netty-handler`。服务端在 JVM 内运行，仓库没有 Netty 服务镜像。WebSocket 回显实验使用 JDK 自带的 `java.net.http.WebSocket` 做集成测试，客户端随 JDK 提供。读空闲实验直接向嵌入式 Channel 注入入站消息，无需运行客户端或服务端进程。[Netty 4.2.21.Final 发布记录](https://netty.io/news/2026/11/16/4-2-21-Final.html)

| 主要功能 | 教程状态 |
| --- | --- |
| NIO 服务端引导、EventLoop 和 Channel Pipeline | 待补 · [WebSocket 回显实验](../../labs/netty-websocket/README.md#服务端怎样处理消息)展示 Pipeline 的用法 |
| HTTP 编解码、WebSocket 握手、控制帧及消息聚合 | 已有教程 · [WebSocket 回显实验](../../labs/netty-websocket/README.md) |
| 读空闲事件与连接关闭 | 已有教程 · [读空闲时回收长连接](#读空闲时回收长连接) |
| 写空闲与双向空闲事件、应用层心跳 | 待补 |
| TCP 自定义协议的帧边界与编解码 | 待补 |
| ByteBuf 池化与引用计数 | 待补 |
| 写缓冲水位线与背压 | 待补 |
| TLS 握手与加密 | 待补 |

### 读空闲时回收长连接

适用于客户端承诺定期发送业务消息或心跳的长连接。例如设备按固定周期上报，网络断开后服务端可能仍持有连接，读空闲事件可触发回收。阈值要覆盖正常上报间隔和抖动。客户端允许长时间静默时，需要先约定心跳或调整回收策略，否则正常连接也会被关闭。

使用 Java 17+ 和仓库 Maven Wrapper。[可运行示例](../../labs/netty-websocket/src/test/java/io/github/nettylab/ReadIdleTimeoutTest.java) 复用 WebSocket 实验的依赖，在每个待监测的连接 Pipeline 中依次添加两个处理器。

```java
channel.pipeline().addLast(new IdleStateHandler(10, 0, 0), new CloseOnReadIdle());
```

三个参数分别是读、写和双向空闲秒数，`0` 关闭对应检测。每个连接创建自己的 `IdleStateHandler`，完成一次入站读取后重新计算读空闲时间。`CloseOnReadIdle` 在 `userEventTriggered` 收到 `READER_IDLE` 时调用 `ctx.close()`，其他事件继续向下传播。将检测器放在可能消费入站消息的处理器之前，可以把这些入站消息也计入读活动。本例直接关闭 Channel。协议需要先发送关闭帧时，应由协议处理器执行关闭流程。

从仓库根目录运行实验。测试冻结 `EmbeddedChannel` 的 EventLoop 时钟，每次推进后显式执行到期的调度任务，无需等待真实时间。

```bash
./mvnw -B -Dmaven.repo.local="$PWD/target/netty-maven-cache" -f labs/netty-websocket/pom.xml clean verify
```

| 模拟时间 | 操作与预期结果 |
| --- | --- |
| 第 6 秒 | 注入一次入站消息，下游收到原消息。 |
| 第 10 秒 | 初始检查点到达，距上次读取只有 4 秒，连接仍活跃。 |
| 第 16 秒 | 距上次读取已满 10 秒，触发读空闲事件，连接关闭。 |

2026-11-23 在 macOS arm64、OpenJDK 17.0.16 上执行上述命令。测试确认第 6 秒的消息到达下游，第 10 秒连接仍活跃，第 16 秒连接已关闭。原有 WebSocket 回显测试也通过。

官方资料

- [Netty 4.2 IdleStateHandler API](https://netty.io/4.2/api/io/netty/handler/timeout/IdleStateHandler.html)
- [Netty 4.2 EmbeddedChannel API](https://netty.io/4.2/api/io/netty/channel/embedded/EmbeddedChannel.html)
- [Netty 4.2.21.Final IdleStateHandler 源码](https://github.com/netty/netty/blob/netty-4.2.21.Final/handler/src/main/java/io/netty/handler/timeout/IdleStateHandler.java)
