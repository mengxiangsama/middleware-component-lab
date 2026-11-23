CREATE TABLE products (
    id BIGINT PRIMARY KEY,
    price DECIMAL(12, 2) NOT NULL CHECK (price >= 0)
);
