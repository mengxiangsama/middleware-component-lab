# ClickHouse + chDB 增量汇总支付指标

## 适用场景

支付报表按北京时间的日期和渠道统计笔数、金额及付款人数。每日看板反复扫描明细时，可以在写入批次时先计算聚合状态，再在读取时合并状态。同一买家跨批次付款，合并后的付款人数仍按买家去重。

| 组件 | 分工 |
| --- | --- |
| [ClickHouse](../../docs/components/search-cache.md#clickhouse) | 以增量物化视图处理每个写入块，将聚合状态写进 `AggregatingMergeTree`。 |
| chDB | 在 Python 进程中运行真实 ClickHouse 引擎，执行建表、插入、回填和对账语句。 |

## 接入步骤

1. 明细表保存 UTC 时间戳、渠道、买家 ID 和以分计的金额，按 `toDate(paid_at, 'Asia/Shanghai')` 计算报表日期。
2. 建一个以日期和渠道排序的 `AggregatingMergeTree` 目标表。将 `countState()`、`sumState(amount_cents)` 和 `uniqExactState(buyer_id)` 写入对应的 `AggregateFunction` 列。增量物化视图的 `TO` 指向这张表。
3. 读取目标表仍按日期和渠道分组，使用 `countMerge`、`sumMerge` 和 `uniqExactMerge`。这样即使目标表的多个数据部件还没合并，查询也能合并各批的状态，得到完整结果。
4. 对视图创建前的明细单独回填。实验暂停新写入，将现有明细用同一聚合语句回填一次，再继续插入。接入运行中的系统时，需要确定一个明确的回填和增量边界，避免把一行同时计入两条路径。

## 本地验证

准备 Python 3.13 和可下载 chDB wheel 的网络。本实验使用 chDB 4.0.2，从仓库根目录执行。可以在虚拟环境里安装依赖。

```sh
python -m pip install -r labs/clickhouse-payment-rollup/requirements.txt
python labs/clickhouse-payment-rollup/verify.py
```

验证脚本在临时目录中创建表。第一笔数据在创建视图前写入，检查其在回填后才进入结果。接下来分两批插入明细，同一买家跨批付款。脚本断言原始明细的聚合结果与目标表的合并结果一致。

| 北京日期 | 渠道 | 笔数 | 金额（分） | 去重买家 |
| --- | --- | --- | --- | --- |
| 2026-11-23 | app | 1 | 1200 | 1 |
| 2026-11-24 | app | 3 | 5500 | 2 |
| 2026-11-24 | web | 1 | 900 | 1 |

实验用每行代表一笔付款的追加明细。重复输入需要接入方按业务事件处理。已写入数据的修改、删除和退款需要另行设计汇总更新方式。实验范围为本地 ClickHouse SQL 和单机表，不涉及集群延迟或查询性能基准。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、Python 3.13.7 上运行脚本，使用 chDB 4.0.2（ClickHouse 引擎 25.8.2.1）。回填前目标表为空。回填和两批增量写入后，原始明细与汇总表的三行结果完全一致。11 月 24 日 app 渠道 3 笔、5500 分、2 个去重买家。

## 官方资料

- [ClickHouse 增量物化视图](https://clickhouse.com/docs/concepts/features/materialized-views/incremental-materialized-view) · [AggregatingMergeTree](https://clickhouse.com/docs/reference/engines/table-engines/mergetree-family/aggregatingmergetree)
- [chDB 4.0.2 发行版](https://github.com/chdb-io/chdb/releases/tag/v4.0.2) · [chDB Session 用法](https://github.com/chdb-io/chdb)
