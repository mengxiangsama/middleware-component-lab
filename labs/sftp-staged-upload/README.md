# SFTP + Apache MINA SSHD 分阶段交付对账 CSV

## 适用场景

合作方定时扫描 SFTP 的 `/outbound` 目录，读取订单对账 CSV。发送端先用临时文件名上传，关闭远程写入句柄后再发布正式文件名。接收端只匹配正式名称，在发布后读取完整报表。发送端同时校验合作方的 SSH 主机公钥，避免将账号凭据和文件发给身份不符的服务端。

| 组件 | 职责 |
| --- | --- |
| Java + Apache MINA SSHD | 校验主机公钥，通过 SFTP 写入临时文件并重命名，读取方使用独立的 SFTP 会话检查交付。 |
| SFTP 服务端 | 通过 SSH 提供远程文件操作。实验使用 Apache MINA SSHD 启动真实服务端，并在本地文件系统执行重命名。 |

## 接入步骤

1. 使用 Java 17 和 Maven 依赖 `org.apache.sshd:sshd-sftp:2.20.0`。该依赖会引入同版本的 `sshd-core`。在连接之前，从合作方可信渠道取得 SSH 主机公钥，通过 `RequiredServerKeyVerifier` 固定这把公钥，再配置上传账号及其认证凭据。
2. 和合作方约定目录及正式文件名匹配规则，例如只读 `/outbound/orders-*.csv`。在同一目录生成另一个唯一的、接收规则不会匹配的临时名。实验使用 `/outbound/.orders-20261124.csv.uploading`，以 `Create`、`Write`、`Exclusive` 打开，防止覆盖已存在的临时文件。
3. 写完 CSV 并成功关闭输出流后，再调用 `rename(staging, report, SftpClient.CopyMode.Atomic)`。实验连接协商 SFTP v6，服务端把原子重命名标志传给同一文件系统上的 `Files.move(..., ATOMIC_MOVE)`。接入前在合作方测试环境确认协议版本或等效扩展、底层文件系统和重命名支持。上传或关闭失败时，流程应停止发布。
4. 重命名得到成功响应后，接收端处理正式文件名。若超时导致结果未知，先核对正式路径及报表内容，再决定是否重试。为重复交付确定命名和幂等策略，接收端可按报表业务 ID 去重。

## 本地验证

准备 Java 17 或更高版本。首次执行需要下载 Maven 依赖，从仓库根目录运行。

```sh
./mvnw -B -ntp -f labs/sftp-staged-upload/pom.xml test
```

JUnit 启动只监听 `127.0.0.1` 临时端口的 SFTP 服务端，运行期间生成 SSH 主机密钥、错误的对照密钥和测试密码，使用临时目录作为 SFTP 根目录。测试会关闭服务端、客户端和文件句柄并清理临时目录。

| 阶段 | 断言 |
| --- | --- |
| 固定错误主机公钥 | 客户端因主机密钥不匹配终止连接（断开原因 `9`），服务端密码认证调用次数为 `0`。 |
| 固定正确主机公钥 | 两条独立的 SSH 会话完成认证，协商 SFTP v6。 |
| 写入及关闭临时文件 | 读取方在写入时和关闭后都找不到正式文件名，关闭后临时文件为 `42` 字节。 |
| 原子重命名后 | 服务端确认重命名，读取方下载正式文件并逐字节比较全部 `42` 字节，同时确认临时路径不存在。 |

## 实测记录

2026-11-24 在 macOS 26.5 ARM64 上使用 OpenJDK 17.0.16、Maven 3.9.11 和 Apache MINA SSHD 2.20.0 运行，1 项集成测试通过。验证范围为单实例本地文件系统、同目录重命名且正式路径最初为空，断言重命名前后的路径状态和文件内容。

## 官方资料

- [Apache MINA SSHD 2.20.0 release](https://github.com/apache/mina-sshd/releases/tag/sshd-2.20.0)
- [Apache MINA SSHD SFTP 客户端和服务端示例](https://github.com/apache/mina-sshd/blob/sshd-2.20.0/docs/sftp.md)
- [SSH 客户端设置与主机密钥验证](https://github.com/apache/mina-sshd/blob/sshd-2.20.0/docs/client-setup.md)
- [SFTP 原子重命名标志实现](https://github.com/apache/mina-sshd/blob/sshd-2.20.0/sshd-sftp/src/main/java/org/apache/sshd/sftp/server/AbstractSftpSubsystemHelper.java#L1206) · [默认文件系统移动实现](https://github.com/apache/mina-sshd/blob/sshd-2.20.0/sshd-sftp/src/main/java/org/apache/sshd/sftp/server/SftpFileSystemAccessor.java#L518)
