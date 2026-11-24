# gRPC + Protocol Buffers 传递订单查询截止时间

## 适用场景

客户端经订单服务查询库存。客户端愿意为整次查询等待两秒时，库存调用也要使用这次请求的剩余时间。库存超时后，客户端可以结束等待，库存服务也能感知取消并停止仍在运行的工作。

| 组件 | 分工 |
| --- | --- |
| [Protocol Buffers](../../docs/components/network-programming.md#protocol-buffers) | 用 `.proto` 约定两个服务的方法、SKU 字段和返回的库存数量，生成 Java 消息类型。 |
| [gRPC Java](../../docs/components/network-programming.md#grpc) | 生成服务端和客户端 Stub，通过 HTTP/2 发起 RPC，在订单服务调用库存时继续传递当前请求的截止时间。 |

调用链是客户端 → 订单服务 `CheckAvailability` → 库存服务 `ReadStock`。库存服务返回数量，订单服务将原始 SKU 和数量一起返回。

## 接入步骤

1. 参考 [order_inventory.proto](src/test/proto/order_inventory.proto) 定义两个服务及其消息。构建时用 `protoc` 生成 Java 消息，用 `protoc-gen-grpc-java` 生成两端的 RPC 接口。示例的两个服务分别监听 127.0.0.1 的动态端口。
2. 客户端用 `withDeadlineAfter(2, SECONDS)` 设置端到端预算。订单服务在入站处理方法中直接用库存 Stub 发起下游 RPC。gRPC Java 从当前 `Context` 读取入站截止时间，并按剩余时间向下游传递。库存 Stub 另外指定截止时间时，gRPC Java 取两者中较早的时间。
3. 库存服务可用 `Context.current().getDeadline()` 读取截止时间，用取消监听或取消检查结束其启动的工作。如果把下游调用移到自建线程池，要在提交任务前捕获并传入原请求的 gRPC `Context`。

## 本地验证

需要 JDK 17+ 和可下载 Maven 依赖的网络。从仓库根目录运行，Maven 会按系统架构下载相应的 Protocol Buffers 编译器与 gRPC 插件。

```sh
./mvnw -B -f labs/grpc-deadline/pom.xml clean verify
```

| 请求 | 验证内容 |
| --- | --- |
| `sku-42`，预算 10 秒 | 客户端通过两跳 RPC 收到 SKU 与库存 7。 |
| `held`，预算 2 秒 | 库存收到短预算，当调用结束时触发取消监听。客户端收到 `DEADLINE_EXCEEDED`。 |

示例使用两个本机服务和可控库存数据，验证 Java Stub 默认传播 deadline 和两跳的取消行为。TLS、重试与异步线程切换按实际接入验证。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、JDK 17.0.16、gRPC Java 1.77.0 和 Protobuf Java 4.33.0 上执行上述命令。1 项集成测试通过。默认的本地库存为 7。暂挂请求抵达库存服务时携带剩余预算，库存服务随后观察到取消，客户端得到 `DEADLINE_EXCEEDED`。

## 官方资料

- [gRPC Deadline 与传播](https://grpc.io/docs/guides/deadlines/) · [gRPC 取消](https://grpc.io/docs/guides/cancellation/)
- [Java 两端代码生成示例](https://grpc.io/docs/languages/java/basics/) · [gRPC Java 1.77.0](https://github.com/grpc/grpc-java/releases/tag/v1.77.0)
- [gRPC Java 选择有效截止时间的源码](https://github.com/grpc/grpc-java/blob/v1.77.0/core/src/main/java/io/grpc/internal/ClientCallImpl.java#L425)
- [Proto3 消息与服务定义](https://protobuf.dev/programming-guides/proto3/) · [Protobuf 33.0](https://github.com/protocolbuffers/protobuf/releases/tag/v33.0)
