# gRPC Java + grpc_health_probe 就绪与存活检查

库存服务启动后可以接收 gRPC 连接，但数据库连接池或缓存可能仍在初始化。应用用标准 gRPC Health 服务维护两个状态。进程能正常运行时，`liveness` 返回 `SERVING`。可以处理库存请求时，`readiness` 再返回 `SERVING`。部署平台可据此判断何时接流量、何时重启容器。

## 接入步骤

1. Java 服务加入 `io.grpc:grpc-services`，通过 `HealthStatusManager.getHealthService()` 注册标准 `grpc.health.v1.Health` 服务。示例使用 `grpc-netty-shaded` 提供传输，两个依赖均为 1.77.0。[Maven 依赖](pom.xml) · [Java 示例](src/main/java/io/github/middlewarelab/grpchealth/HealthServer.java)
2. 用 `setStatus("liveness", SERVING)` 和 `setStatus("readiness", NOT_SERVING)` 建立初始状态。应用根据真实依赖的就绪变化更新 `readiness`。示例用终端命令触发变化，并让空服务名的状态与 `readiness` 一起更新。
3. 探针使用 `grpc.health.v1.Health/Check` 发起 RPC，请求中的服务名对应应用注册的名称。按服务名分别查询就绪和存活。用发布包中的 `grpc_health_probe` 核对响应状态和退出码。[标准 gRPC 健康检查](https://grpc.io/docs/guides/health-checking/)

## 运行与验证

准备 JDK 17+、Python 3 和 [grpc_health_probe 0.4.63](https://github.com/grpc-ecosystem/grpc-health-probe/releases/tag/v0.4.63)。选择与本机匹配的发行文件，对照官方 `checksums.txt` 校验 SHA-256 并加上执行权限。在仓库根目录运行以下命令，把第二条命令中的路径换成本机路径。

```sh
./mvnw -B -ntp -f labs/grpc-health-probe/pom.xml clean package
python3 -B labs/grpc-health-probe/verify.py --probe /path/to/grpc_health_probe
```

探针在 `PATH` 中时可以省略 `--probe`。验证脚本在环回地址启动一个真实 gRPC Java 进程，使用操作系统分配的端口。它用官方二进制发请求，在同一进程中切换状态，最后停止实验进程。

| 场景 | 就绪探针 | 存活探针 | 省略服务名 |
| --- | --- | --- | --- |
| 启动后，依赖未就绪 | `NOT_SERVING`（4） | `SERVING`（0） | `NOT_SERVING`（4） |
| 依赖就绪 | `SERVING`（0） | `SERVING`（0） | `SERVING`（0） |
| 依赖暂时不可用 | `NOT_SERVING`（4） | `SERVING`（0） | `NOT_SERVING`（4） |
| 依赖恢复 | `SERVING`（0） | `SERVING`（0） | `SERVING`（0） |

括号内是已固定版本的 CLI 退出码。未注册的服务名还会得到 gRPC `NOT_FOUND`，CLI 退出 3，可以区分配置错误与服务明确报告未就绪。[0.4.63 退出码](https://github.com/grpc-ecosystem/grpc-health-probe/tree/v0.4.63#exit-codes)

2026-11-28 在 macOS 26.5 ARM64、OpenJDK 17.0.16 和 Python 3.13.7 上使用校验过的 macOS ARM64 探针二进制联调。就绪和空服务名的退出码均为 `4 → 0 → 4 → 0`，存活始终为 `0`，未知服务名为 `3`。

### 手动观察状态

在一个终端启动实验服务。

```sh
java -jar labs/grpc-health-probe/target/grpc-health-probe-lab.jar 50051
```

在另一个终端分别运行两条命令。回到服务终端输入 `ready` 并回车，再重跑就绪探针。可以输入 `unready` 模拟依赖故障，或输入 `stop` 结束服务。

```sh
grpc_health_probe -addr=127.0.0.1:50051 -service=readiness
grpc_health_probe -addr=127.0.0.1:50051 -service=liveness
```

### 搭配 Kubernetes 探针

Kubernetes 内建 gRPC 探针的 `service` 对应上面的健康服务名。服务监听 Pod IP 可达的端口后，在容器配置中加入以下字段。就绪失败会使 Pod 暂时退出 Service 的正常流量，连续存活失败达到阈值时 kubelet 会重启容器。

```yaml
readinessProbe:
  grpc:
    port: 50051
    service: readiness
livenessProbe:
  grpc:
    port: 50051
    service: liveness
```

内建 gRPC 探针使用数字端口，连接 Pod IP。实验程序只监听 `127.0.0.1`。部署时配置实际服务对 Pod IP 监听，并按环境设定超时和失败阈值。以上字段放入 Deployment 后，通过了 Kubernetes 1.35.0 的严格 Schema 校验。实际联调覆盖本机 Health RPC 和官方探针，Kubernetes 流量摘除和容器重启需在目标集群观察。[Kubernetes gRPC 探针](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/#define-a-grpc-liveness-probe)

## 官方资料

- [gRPC Java HealthStatusManager 1.77.0](https://github.com/grpc/grpc-java/blob/v1.77.0/services/src/main/java/io/grpc/protobuf/services/HealthStatusManager.java) · [Java 健康服务示例](https://github.com/grpc/grpc-java/tree/v1.77.0/examples/src/main/java/io/grpc/examples/healthservice)
- [gRPC 健康协议](https://github.com/grpc/grpc-proto/blob/master/grpc/health/v1/health.proto) · [grpc-health-probe 0.4.63](https://github.com/grpc-ecosystem/grpc-health-probe/tree/v0.4.63)
