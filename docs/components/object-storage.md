# 对象存储

## SeaweedFS

SeaweedFS 的 S3 网关按 bucket 和对象键管理文件，可用 S3 SDK 上传和读取。应用可把订单附件或报表放在对象存储中，在业务数据库保存对象键和上传状态。

- **接入**。应用配置 S3 endpoint、区域和服务端凭据。在本地回环地址运行 `weed mini` 时，使用临时数据目录。连接本实验的 SeaweedFS endpoint 时，AWS SDK for Go v2 使用 path-style 地址。
- **搭配 AWS SDK for Go v2**。服务端为特定对象生成短时预签名 URL，浏览器或普通 HTTP 客户端持 URL 直传。应用用 `HeadObject` 核对大小和元数据，再记录上传完成。该 URL 在有效期内可重复使用，完成状态可由业务记录去重。
- **验证**。运行 [SeaweedFS + AWS SDK 直传报表](../../labs/seaweedfs-s3-presign/README.md)，核对预签名上传、下载、内容摘要和对象键约束。

[SeaweedFS S3 API](https://github.com/seaweedfs/seaweedfs/wiki/Amazon-S3-API) · [AWS SDK for Go v2 S3 示例](https://docs.aws.amazon.com/sdk-for-go/v2/developer-guide/go_s3_code_examples.html#create-a-presigned-url)
