# Apache Camel 将 CSV 导入 HTTP 接口后归档

## 适用场景

外部系统把库存 CSV 交到文件夹，接收端提供 HTTP 导入接口。接口短暂返回 503 时，同一份文件需要再发送一次。接收端确认成功后再移动原文件，便于观察哪些批次已经投递。

| 组件 | 分工 |
| --- | --- |
| [Apache Camel](../../docs/components/data-pipelines.md#apache-camel) | 编排文件读取、请求发送和异常处理。 |
| Camel File | 读取本地输入目录，路由成功后用 `move` 移到归档目录。 |
| Camel HTTP | 把文件内容作为 `text/csv` POST 到导入接口，根据 HTTP 状态报告成功或异常。 |

## 接入步骤

1. 使用同一版本的 Camel 核心、`camel-file` 和 `camel-http`。为 File 端点配置输入目录、文件名和 `move=.archive`。实验在路由启动前写完 `orders.csv`。实际接入时，生产方可先写临时名并在完成后改名，使路由只拾取写完的文件。
2. 把文件内容转成 UTF-8 字符串，给 HTTP 请求设置 `POST` 和 `Content-Type: text/csv; charset=UTF-8`。HTTP 端点保留 `throwExceptionOnFailure=true`，让 503 进入异常处理。
3. 用 `onException(HttpOperationFailedException.class)` 匹配 HTTP 异常，只对 503 配置 `maximumRedeliveries(1)`。重投从失败的 HTTP 步骤开始。实验禁用 HTTP 组件底层自动重试，便于确认第二次请求来自 Camel 异常处理。

这里成功指本地接收端返回 HTTP 204。接入实际服务时，文件投递重试可通过批次幂等键处理已导入但未收到确认的情况。

## 本地验证

需要 JDK 17+ 和可下载 Maven 依赖的网络。从仓库根目录运行下面的命令。

```sh
./mvnw -B -f labs/camel-file-http-import/pom.xml clean verify
```

[测试代码](src/test/java/io/github/middlewarelab/camelfile/FileHttpImportTest.java) 启动真实 Camel 路由和回环地址上的 JDK HTTP 测试服务。

| 阶段 | 核对结果 |
| --- | --- |
| 首次 POST | 本机服务接收 CSV 并返回 503。 |
| Camel 重投 | 路由记录一次重投，两次 POST 的字节完全一致。第二次响应暂挂时，原文件仍在输入目录，归档文件尚未出现。 |
| 接收端返回 204 | 原文件移动到 `inbox/.archive/orders.csv`，归档字节与输入一致。 |

实验覆盖一次 503 和一次成功响应。远端数据持久化、重启恢复及最终失败处置需在实际环境验证。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、JDK 17.0.16 和 Apache Camel 4.18.0 上执行上述命令，1 项集成测试通过。一次 Camel 重投产生两次相同的 POST，第二次请求等待接收端返回 204 时文件仍在原处。收到 204 后，文件进入 `.archive`，归档内容与发送内容一致。

## 官方资料

- [Apache Camel 4.18.0](https://camel.apache.org/releases/release-4.18.0/) · [固定版本源码](https://github.com/apache/camel/tree/camel-4.18.0)
- [File 组件与移动选项](https://camel.apache.org/components/4.18.x/file-component.html) · [HTTP 组件](https://camel.apache.org/components/4.18.x/http-component.html) · [异常处理与重投](https://camel.apache.org/manual/exception-clause.html)
