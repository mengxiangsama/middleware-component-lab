from contextlib import closing
from pathlib import Path
from tempfile import TemporaryDirectory

import chdb
from chdb.session import Session


AGGREGATE = """
SELECT
    toDate(paid_at, 'Asia/Shanghai') AS paid_day,
    channel,
    countState() AS orders_state,
    sumState(amount_cents) AS amount_state,
    uniqExactState(buyer_id) AS buyers_state
FROM payment_events
GROUP BY paid_day, channel
"""

READ_ROLLUP = """
SELECT paid_day, channel,
    countMerge(orders_state),
    sumMerge(amount_state),
    uniqExactMerge(buyers_state)
FROM daily_payments
GROUP BY paid_day, channel
ORDER BY paid_day, channel
"""

READ_RAW = """
SELECT toDate(paid_at, 'Asia/Shanghai') AS paid_day, channel,
    count(), sum(amount_cents), uniqExact(buyer_id)
FROM payment_events
GROUP BY paid_day, channel
ORDER BY paid_day, channel
"""


def expect(session, sql, expected):
    actual = str(session.query(sql, "TSV")).strip()
    if actual != expected:
        raise AssertionError(f"Expected {expected!r}, got {actual!r}")


def verify(session):
    engine = str(session.query("SELECT version()", "TSV")).strip()
    print(f"chDB {chdb.__version__}, ClickHouse {engine}")
    session.query("""
        CREATE TABLE payment_events (
            order_id UInt64,
            paid_at DateTime('UTC'),
            channel String,
            buyer_id UInt64,
            amount_cents UInt64
        ) ENGINE = MergeTree
        ORDER BY (paid_at, order_id)
    """)
    session.query("""
        CREATE TABLE daily_payments (
            paid_day Date,
            channel String,
            orders_state AggregateFunction(count),
            amount_state AggregateFunction(sum, UInt64),
            buyers_state AggregateFunction(uniqExact, UInt64)
        ) ENGINE = AggregatingMergeTree
        ORDER BY (paid_day, channel)
    """)
    session.query("""
        INSERT INTO payment_events VALUES
        (1, '2026-11-23 15:59:59', 'app', 42, 1200)
    """)

    session.query(f"CREATE MATERIALIZED VIEW daily_payments_mv TO daily_payments AS {AGGREGATE}")
    expect(session, "SELECT count() FROM daily_payments", "0")
    session.query(f"INSERT INTO daily_payments {AGGREGATE}")
    expect(session, READ_ROLLUP, "2026-11-23\tapp\t1\t1200\t1")
    print("pre-existing payment appears after explicit backfill")

    session.query("""
        INSERT INTO payment_events VALUES
        (2, '2026-11-23 16:00:00', 'app', 42, 1900),
        (3, '2026-11-23 16:05:00', 'app', 7, 3100)
    """)
    session.query("""
        INSERT INTO payment_events VALUES
        (4, '2026-11-24 03:00:00', 'app', 42, 500),
        (5, '2026-11-24 04:00:00', 'web', 7, 900)
    """)
    expected = (
        "2026-11-23\tapp\t1\t1200\t1\n"
        "2026-11-24\tapp\t3\t5500\t2\n"
        "2026-11-24\tweb\t1\t900\t1"
    )
    expect(session, READ_ROLLUP, expected)
    expect(session, READ_RAW, expected)
    print("day\tchannel\torders\tamount_cents\tunique_buyers")
    print(expected)


if __name__ == "__main__":
    with TemporaryDirectory(prefix="clickhouse-payment-") as directory:
        with closing(Session(str(Path(directory) / "data"))) as session:
            verify(session)
