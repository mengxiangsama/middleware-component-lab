CREATE TABLE IF NOT EXISTS shop_order (
    order_id VARCHAR(64) PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'PAID'))
);

CREATE TABLE IF NOT EXISTS loyalty_account (
    customer_id VARCHAR(64) PRIMARY KEY,
    points INTEGER NOT NULL DEFAULT 0 CHECK (points >= 0)
);

CREATE TABLE IF NOT EXISTS loyalty_ledger (
    order_id VARCHAR(64) NOT NULL,
    reward_type VARCHAR(32) NOT NULL,
    customer_id VARCHAR(64) NOT NULL REFERENCES loyalty_account (customer_id),
    points INTEGER NOT NULL CHECK (points > 0),
    PRIMARY KEY (order_id, reward_type)
);
