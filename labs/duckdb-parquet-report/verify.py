from contextlib import closing
from pathlib import Path
from tempfile import TemporaryDirectory

import duckdb


REPORT = """
SELECT channel, count(*) AS orders, sum(amount_cents) AS amount_cents
FROM read_parquet(?, hive_partitioning = true)
WHERE order_day = DATE '2026-11-24'
GROUP BY channel
ORDER BY channel
"""


def verify(root):
    destination = root / "orders"
    parquet_glob = str(destination / "order_day=*" / "*.parquet")
    with closing(duckdb.connect()) as database:
        database.execute("SET threads=1")
        database.execute("""
            CREATE TABLE orders (order_day DATE, channel VARCHAR, amount_cents BIGINT)
        """)
        database.execute("""
            INSERT INTO orders VALUES
                ('2026-11-23', 'app', 9999),
                ('2026-11-23', 'web', 800),
                ('2026-11-24', 'app', 1200),
                ('2026-11-24', 'app', 2800),
                ('2026-11-24', 'web', 700)
        """)
        database.execute(
            "COPY orders TO ? (FORMAT PARQUET, PARTITION_BY (order_day))",
            [str(destination)],
        )

        partitions = sorted(path.parent.name for path in destination.glob("*/*.parquet"))
        expected_partitions = ["order_day=2026-11-23", "order_day=2026-11-24"]
        if partitions != expected_partitions:
            raise AssertionError(f"Unexpected day partitions: {partitions}")

        report = database.execute(REPORT, [parquet_glob]).fetchall()
        if report != [("app", 2, 4000), ("web", 1, 700)]:
            raise AssertionError(f"Unexpected selected-day report: {report}")
        plan = database.execute("EXPLAIN " + REPORT, [parquet_glob]).fetchall()[0][1]
        if "Scanning Files: 1/2" not in plan:
            raise AssertionError(f"Expected the day filter to prune one file:\n{plan}")
        print(f"DuckDB {duckdb.__version__}")
        print(f"2026-11-24 (channel, orders, cents): {report}")
        print("Scanning Files: 1/2")


if __name__ == "__main__":
    with TemporaryDirectory(prefix="duckdb-orders-") as directory:
        verify(Path(directory))
