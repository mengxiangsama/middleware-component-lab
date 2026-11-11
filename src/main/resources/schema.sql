create table if not exists orders (
    order_id varchar(64) primary key,
    product_name varchar(200) not null,
    amount decimal(18, 2) not null,
    status varchar(32) not null,
    created_at timestamp not null
);

create table if not exists local_transaction (
    transaction_id varchar(64) primary key,
    transaction_state varchar(32) not null,
    updated_at timestamp not null
);

create table if not exists consumed_event (
    event_id varchar(128) primary key,
    order_id varchar(64) not null,
    consumed_at timestamp not null
);
