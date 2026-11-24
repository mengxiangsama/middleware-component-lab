# SeaweedFS + AWS SDK 临时上传对账报表

## 适用场景

结算后台允许门店上传 CSV 报表。应用先确认门店权限并确定对象键，再给上传端一个短时 URL。上传端直接连接对象存储，应用保管签名凭据并核对上传结果。

| 组件 | 分工 |
| --- | --- |
| [SeaweedFS](../../docs/components/object-storage.md#seaweedfs) | 验证 S3 请求签名，保存对象和元数据。 |
| AWS SDK for Go v2 | 为指定对象生成预签名 PUT、GET URL，并用 `HeadObject` 检查存储结果。 |
| 普通 HTTP 客户端 | 按 SDK 返回的 HTTP 方法、URL 和签名请求头，上传或下载 CSV。 |

## 接入步骤

1. 在应用端初始化 S3 客户端。为 SeaweedFS 设置 `BaseEndpoint` 和 `UsePathStyle: true`，从服务端的凭据配置取密钥。预创建业务 bucket。
2. 为业务记录分配对象键。实验使用 `report-attachments/reconciliation/day-report.csv`，把对象键、文件大小和类型交给 `PresignPutObject`，签出 60 秒有效的 PUT URL。把 URL、HTTP 方法和 `SignedHeader` 交给客户端。
3. 客户端将文件作为请求正文直接 PUT，保留签名要求的请求头。实验用 `DisableHeaderHoisting` 将 `X-Amz-Meta-Source` 保留为请求头，SeaweedFS 能将它写入对象元数据。
4. 上传后，应用以 `HeadObject` 核对对象大小、内容类型和业务元数据。需要客户端下载时，应用重新确认访问资格并通过 `PresignGetObject` 生成 GET URL。实验对下载内容计算 SHA-256，核对它与原文件一致。

浏览器按 URL 和已知大小的文件正文生成 `Host`、`Content-Length`。跨域调用时，按实际前端地址配置对象存储的 CORS 方法和请求头。预签名 URL 在有效期内可重复使用，请按访问凭据处理。

## 本地验证

需要 Go 1.24+ 和 SeaweedFS 4.55 的 `weed`。下载并解压系统对应的官方发布包。Apple Silicon 对应的文件是 `darwin_arm64.tar.gz`。[SeaweedFS 4.55 发布](https://github.com/seaweedfs/seaweedfs/releases/tag/4.55)

从仓库根目录运行。将命令中的二进制路径替换为实际路径。

```sh
(cd labs/seaweedfs-s3-presign && WEED_BINARY=/absolute/path/to/weed go test -count=1 -v ./...)
```

测试在回环地址启动真实的 `weed mini`，创建临时目录和随机签名凭据，结束时停止进程。测试检查以下结果。

- 普通 HTTP 客户端使用 60 秒签名 URL 上传和下载 CSV。
- SDK 查询到的文件大小、`text/csv` 和 `source` 元数据与预期相符，下载内容的 SHA-256 与原始文件一致。
- 将同一 PUT URL 改为另一个对象键后，SeaweedFS 返回 HTTP 403。直接匿名读取原对象也返回 403。

本实验覆盖单节点 SeaweedFS、单对象读写和实际签名校验。生产环境需按部署配置验证 TLS、浏览器 CORS、分片上传和授权策略。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、Go 1.24.2、SeaweedFS 4.55 和 AWS SDK for Go v2 S3 模块 1.95.0 上执行上述命令，1 项集成测试通过。预签名 PUT、GET 均返回 200，元数据和 SHA-256 一致。修改对象键与匿名 GET 各返回 403。使用官方发布页的 SHA-256 核验了 ARM64 发行包。

## 官方资料

- [SeaweedFS S3 API](https://github.com/seaweedfs/seaweedfs/wiki/Amazon-S3-API) · [weed mini 快速入门](https://github.com/seaweedfs/seaweedfs/wiki/Quick-Start-with-weed-mini)
- [AWS SDK for Go v2 预签名上传示例](https://docs.aws.amazon.com/sdk-for-go/v2/developer-guide/go_s3_code_examples.html#create-a-presigned-url) · [S3 预签名 URL 生命周期](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)
