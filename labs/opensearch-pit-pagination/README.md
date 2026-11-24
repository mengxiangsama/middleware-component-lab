# OpenSearch + Python 订单对账的稳定分页

## 适用场景

对账任务需要按订单更新时间顺序导出搜索结果。导出第一页后，补录订单和状态变更仍会写入索引。用 OpenSearch Point in Time（PIT）固定这次导出看到的数据，再用 `search_after` 逐页读取。同一时间的新查询继续读取刷新后的数据。

| 组件 | 职责 |
| --- | --- |
| OpenSearch | 保存订单文档，提供 PIT 搜索视图、排序和 `search_after`。索引可在导出期间继续接受写入。 |
| Python 对账任务 | 创建 PIT，把上一页最后一条的完整 `sort` 数组当作下一页游标，读取完成后释放 PIT。 |

## 接入步骤

1. 将更新时间映射为 `date`，将订单 ID 映射为 `keyword`。搜索时按 `updated_at` 升序、`order_id` 升序排列，确保多条订单更新时间相同时也有确定顺序。选择在查询范围内唯一的业务字段做最后一个排序键。
2. 通过 `POST /{index}/_search/point_in_time?keep_alive=1m&allow_partial_pit_creation=false` 创建 PIT。它固定创建时可搜索的数据，本例先使用 `_refresh`，确保样例文档已进入搜索视图。
3. 向 `POST /_search` 发请求，在请求体填入 `pit.id`、`size`、`query` 和 `sort`。后续页沿用查询和排序，把上一页最后一条命中的整个 `sort` 数组原样放入 `search_after`，继续使用同一个 PIT。拿到空页即完成遍历。
4. 根据遍历时长设置 `keep_alive`，在结束或异常时通过 `DELETE /_search/point_in_time` 传入自己的 PIT ID。PIT 会暂时保留搜索所需的索引段。需要继续同一次导出时，应保留 PIT ID 和游标，PIT 到期后重新开始导出。

## 本地验证

准备 Python 3 和 OpenSearch 3.10.0 支持的 Java。下面是 macOS ARM64 与 Java 25 的运行命令，从仓库根目录执行。解压时跳过压缩包内的 Linux JDK，使用本机的 Java。

```sh
workdir=$(mktemp -d)
artifact=opensearch-min-3.10.0-linux-arm64.tar.gz
curl -fsSL -o "$workdir/$artifact" \
    "https://artifacts.opensearch.org/releases/core/opensearch/3.10.0/$artifact"
printf '%s  %s\n' 1c7bc3cd7349d4f8e1c43a429df10c4461c576bc8ca0d9da706dcfe33a7d6a63 \
    "$workdir/$artifact" | shasum -a 256 -c -
tar -xzf "$workdir/$artifact" -C "$workdir" \
    --exclude='opensearch-3.10.0/jdk' --exclude='opensearch-3.10.0/jdk/*'
OPENSEARCH_JAVA_HOME="$(/usr/libexec/java_home -v 25)" \
    sh labs/opensearch-pit-pagination/run.sh "$workdir/opensearch-3.10.0"
```

脚本将配置复制到临时目录，启用 512 MiB JVM 堆，启动只监听 `127.0.0.1` 临时端口的单节点实例。官方最小发行包适用于此类一次性实验，实例使用 HTTP 和人工样例数据。脚本关闭自己的 PIT，删除测试索引，停止进程并清理本次运行的临时目录。

| 验证阶段 | 断言结果 |
| --- | --- |
| 获取 PIT 第 1 页 | `O101`、`O102` 更新时间相同，按订单 ID 排序。下一页游标从 `O102` 的 `sort` 数组获取。 |
| 在两页之间更新索引 | 补录 `O105`，将 `O103` 改为 `FULFILLED` 并把更新时间从 `09:02` 改到 `09:05`，然后刷新索引。 |
| 获取原 PIT 第 2、3 页 | 第 2 页是 `O103` 和 `O104`，且 `O103` 仍是 `PAID`。第 3 页为空。 |
| 不带 PIT 发起新查询 | 得到 `O101`、`O102`、`O105`、`O104`、`O103`，且 `O103` 为 `FULFILLED`。 |

## 实测记录

2026-11-24 在 macOS 26.5 ARM64 上用 OpenJDK 25 和 Python 3.13.7 运行官方 OpenSearch 3.10.0 最小发行包。第 1 页游标为 `[1795510800000, "O102"]`。原 PIT 保留 `O103` 的 `PAID` 状态，新查询显示 `FULFILLED` 并包含刚补录的 `O105`。删除 PIT 返回成功。验证范围为单节点、单分片和一次中途刷新。下载文件的 SHA-256 为 `1c7bc3cd7349d4f8e1c43a429df10c4461c576bc8ca0d9da706dcfe33a7d6a63`。

## 官方资料

- [OpenSearch 3.10.0 发布记录](https://github.com/opensearch-project/OpenSearch/releases/tag/3.10.0) · [官方发行包](https://opensearch.org/artifacts/by-version/) · [Tarball 安装和 macOS Java 设置](https://docs.opensearch.org/latest/install-and-configure/install-opensearch/tar/)
- [Point in Time API](https://docs.opensearch.org/latest/api-reference/search-apis/point-in-time-api/) · [PIT 和 `search_after` 分页](https://docs.opensearch.org/latest/search-plugins/searching-data/paginate/)
