# DuckDB + Parquet 按日期分区核对订单

## 适用场景

每日订单导出按日期积累。财务只核对某一天时，让查询用目录上的日期过滤文件，再按渠道汇总明细。Parquet 保存列式数据，[DuckDB](../../docs/components/search-cache.md#duckdb) 在本机执行导出与读取。Hive 分区目录把日期编码成 `order_day=YYYY-MM-DD`，查询可从目录识别分区字段。

## 接入步骤

1. 在导出前确定业务日期和金额单位。实验将日期存为 `DATE`，金额存为整数分。
2. 使用 `COPY ... (FORMAT PARQUET, PARTITION_BY (order_day))` 生成按日期划分的目录。保持各分区的文件字段兼容。
3. 使用 `read_parquet(path, hive_partitioning = true)` 读取。用 `WHERE order_day = DATE '2026-11-24'` 限定日期，再按渠道计算 `count(*)` 与 `sum(amount_cents)`。用 `EXPLAIN` 检查文件扫描范围，核对计划中只需要的分区是否被选中。

## 本地验证

准备 Python 3.13 和可下载依赖的网络，从仓库根目录创建虚拟环境后运行。

```sh
python3.13 -m venv .venv-duckdb
.venv-duckdb/bin/python -m pip install -r labs/duckdb-parquet-report/requirements.txt
.venv-duckdb/bin/python labs/duckdb-parquet-report/verify.py
```

脚本把两天共五笔订单写入临时目录，确认生成两个日期分区。选择 11 月 24 日后，app 为 2 笔、4000 分，web 为 1 笔、700 分，查询计划应显示 `Scanning Files: 1/2`。验证范围为本地 Parquet 分区和金额汇总，真实导出保留、对象存储和大数据量下的性能由实际环境复核。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64、Python 3.13.7 和 DuckDB 1.4.4 上运行实验，两个分区的 Parquet 文件均已生成。所选日期的两个渠道汇总与预期一致，DuckDB 的物理计划显示 `Scanning Files: 1/2`。

## 官方资料

- [DuckDB 1.4.4](https://github.com/duckdb/duckdb/releases/tag/v1.4.4) · [Parquet 读写](https://duckdb.org/docs/stable/data/parquet/overview.html)
- [Hive 分区读取与过滤](https://duckdb.org/docs/stable/data/partitioning/hive_partitioning.html) · [按列分区写出](https://duckdb.org/docs/stable/data/partitioning/partitioned_writes.html)
