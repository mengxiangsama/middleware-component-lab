# Meilisearch 商品关键词搜索与库存过滤

## 适用场景

商品页需要同时按名称搜索、按类目和库存筛选，并在相关商品中显示价格顺序。商品应用负责整理 SKU、名称、类目、库存状态和价格。Meilisearch 索引这些字段，通过一个 HTTP 请求返回匹配结果。本地脚本模拟商品导入与查询，使用五条 JSON 样本。

## 接入步骤

1. 创建 `products` 索引，以稳定的 SKU 字段 `id` 为主键。用 `PATCH /indexes/products/settings` 设置 `searchableAttributes: ["title"]`、`filterableAttributes: ["category", "in_stock"]` 和 `sortableAttributes: ["price_cents"]`。`in_stock` 使用 JSON 布尔值，价格用整数分。
2. 通过 `POST /indexes/products/documents` 批量写入商品。建索引、改设置和写文档都返回 HTTP 202 与 `taskUid`。后续步骤开始前，用 `GET /tasks/{taskUid}` 等待上一项变成 `succeeded`。出现 `failed` 时检查任务的 `error`。
3. 查询应用向 `POST /indexes/products/search` 发送以下请求。需要反向价格顺序时把 `asc` 改为 `desc`。默认排名规则中，相关性也会影响排序，因此应以实际关键词和数据核对顺序。

```json
{
  "q": "keyboard",
  "filter": "category = \"keyboards\" AND in_stock = true",
  "sort": ["price_cents:asc"],
  "attributesToRetrieve": ["id", "price_cents"]
}
```

4. 库存变化时，使用 `PUT /indexes/products/documents` 按主键更新已有文档中变化的字段，等待任务成功后再查询。搜索页使用索引里的库存快照，下单时仍需到业务库存校验可售数量。

## 本地验证

准备 Python 3.9+ 和 [Meilisearch v1.58.0](https://github.com/meilisearch/meilisearch/releases/tag/v1.58.0) 中对应平台的社区版二进制。给下载的文件添加执行权限。在仓库根目录运行下面的命令，或将 `meilisearch` 加入 `PATH` 后省略 `--meilisearch`。

```sh
python3 labs/meilisearch-storefront/verify.py --meilisearch /absolute/path/to/meilisearch
```

实验使用 Python 标准库，并在回环地址启动一个独立的 Meilisearch 进程。脚本生成本次实例所用的临时密钥，退出后关闭进程并清理数据目录。业务环境应分别给商品写入端和查询端使用各自权限的密钥。

| 步骤 | 核对结果 |
| --- | --- |
| 初始化 | 建索引、更新设置和批量导入的任务均为 `succeeded`。 |
| 只搜关键词 | 四条商品命中，包括缺货键盘 `kb-clearance` 和配件 `cover`。 |
| 加类目和库存过滤并切换排序方向 | 只返回 `kb-compact` 与 `kb-pro`。低价优先时前者在前，高价优先时后者在前。 |
| 更新库存 | 将 `kb-compact` 更新为缺货，任务成功后重新搜索，只返回 `kb-pro`。 |

## 实测记录

2026-11-24 在 macOS 26.5 ARM64 和 Python 3.13.7 上，使用官方社区版 Meilisearch 1.58.0 的 Apple Silicon 二进制执行上述命令，校验了发布页的 SHA-256。四个任务都成功。升序返回 `kb-compact`（8900 分）和 `kb-pro`（14900 分），降序反转，库存更新后只返回 `kb-pro`。实验限于单实例的本地商品数据，未接入实际商品数据库或下单链路。

## 官方资料

- [电商搜索示例](https://www.meilisearch.com/docs/resources/demos/ecommerce) · [过滤条件](https://www.meilisearch.com/docs/capabilities/filtering_sorting_faceting/getting_started) · [查询排序](https://www.meilisearch.com/docs/capabilities/filtering_sorting_faceting/how_to/sort_results)
- [POST 搜索](https://www.meilisearch.com/docs/reference/api/search/search-with-post) · [查询异步任务](https://www.meilisearch.com/docs/reference/api/async-task-management/get-task) · [局部更新文档](https://www.meilisearch.com/docs/reference/api/documents/add-or-update-documents)
- [Meilisearch 组件索引](../../docs/components/search-cache.md#meilisearch)
