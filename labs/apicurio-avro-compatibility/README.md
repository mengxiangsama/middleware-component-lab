# Apicurio Registry + Apache Avro Schema 演进

订单服务持续发布 `OrderCreated` 事件，下游消费者升级后仍要读取队列里的旧订单。Apache Avro 定义事件结构和新旧 schema 的读取规则，Apicurio Registry 管理 `.avsc` 版本并在注册时执行兼容性检查。给订单事件加币种时，可以给新字段设默认值，让新消费者处理旧消息时得到明确的值。

这里对 `orders/order-created` 设置 artifact 级 `COMPATIBILITY=BACKWARD`。Registry 用新 schema 作为 reader，检查它能否读取上一版本写入的数据。`BACKWARD` 检查上一版本。若业务要覆盖所有历史版本，Registry 还提供 `BACKWARD_TRANSITIVE`。 [Apicurio 兼容性模式](https://www.apicur.io/registry/docs/apicurio-registry/3.3.x/getting-started/assembly-registry-compatibility-modes.html)

## 运行实验

准备 Python 3、Java 21 和 Apicurio Registry 3.3.3 的 `apicurio-registry-app-3.3.3-all.tar.gz`。用发行页提供的 SHA-256 校验后解压，保持 `quarkus-run.jar` 和同目录依赖一起解压。 [官方发行页](https://github.com/Apicurio/apicurio-registry/releases/tag/3.3.3) · [服务端发行包](https://github.com/Apicurio/apicurio-registry/releases/download/3.3.3/apicurio-registry-app-3.3.3-all.tar.gz)

在仓库根目录运行，传入解压出的 `quarkus-run.jar` 路径：

```bash
python3 labs/apicurio-avro-compatibility/verify.py /path/to/quarkus-run.jar
```

也可以用 `--java /path/to/java` 指定 Java 21。脚本在临时目录启动 Apicurio Registry，使用本机回环端口和 H2 内存存储。Python 等待 Core Registry API 可用，再依次发送请求。实验结束时关闭 Registry 并清理工作目录。

## 注册与验证

以下步骤使用 `/apis/registry/v3`。在实际发布流程中，可用相同操作预先注册事件 schema，并把注册结果作为发布条件。

1. 调用 `POST /groups/orders/artifacts`，用 `artifactType=AVRO` 和 `firstVersion.content` 注册 [v1](schemas/v1.avsc)。这个首次发布的版本只有 `orderId` 和 `totalCents`。
2. 调用 `POST /groups/orders/artifacts/order-created/rules`，请求体为 `{"ruleType":"COMPATIBILITY","config":"BACKWARD"}`，再调用 `GET /groups/orders/artifacts/order-created/rules/COMPATIBILITY` 查看生效值。
3. 调用 `POST /groups/orders/artifacts/order-created/versions`，用 `content.content` 提交 `.avsc` 字符串，`content.contentType` 为 `application/json`。提交 [v2](schemas/v2.avsc) 时，`currency` 有默认值 `CNY`，允许新 schema 读取缺少该字段的旧事件。提交 [v3 候选](schemas/v3-incompatible.avsc) 时，必填 `warehouseId` 缺少默认值，Registry 应返回兼容性错误。
4. 调用 `GET /groups/orders/artifacts/order-created/versions`，确认已发布列表里仍只有版本 `1` 和 `2`。

## 实测结果

2026-11-24 用官方 Apicurio Registry 3.3.3 发行包，在 macOS 26.5 ARM64、Java 21.0.5 和 Python 3.13.7 上运行。官方包内含 Apache Avro 1.12.2 和 H2 2.4.240。实验覆盖单个 artifact 的注册检查和版本列表。

```text
Apicurio Registry 3.3.3
v1: HTTP 200, AVRO artifact created
rule: COMPATIBILITY=BACKWARD
v2: HTTP 200, currency with default accepted
v3: HTTP 400, RuleViolationException for warehouseId
PASS: published versions remain 1, 2
```

## 官方资料

- [Apicurio Registry 3.3.3](https://github.com/Apicurio/apicurio-registry/releases/tag/3.3.3)、[Core Registry API 示例](https://www.apicur.io/registry/docs/apicurio-registry/3.3.x/getting-started/assembly-managing-registry-artifacts-api.html)和 [3.3.3 OpenAPI 定义](https://github.com/Apicurio/apicurio-registry/blob/3.3.3/common/src/main/resources/META-INF/openapi.json)
- [Apicurio GitHub 上的 Avro 接入示例](https://github.com/Apicurio/apicurio-registry/blob/3.3.3/examples/README.md#simple-avro-example)
- [Apache Avro schema 解析规则](https://avro.apache.org/docs/1.12.0/specification/#schema-resolution)
