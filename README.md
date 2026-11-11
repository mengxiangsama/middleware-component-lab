# RocketMQ Transaction Lab

A small, production-minded Spring Boot example that demonstrates the complete Apache RocketMQ transactional-message lifecycle:

1. the producer sends a half message;
2. the broker asks the producer to execute a local transaction;
3. the producer commits, rolls back, or returns an unknown state;
4. RocketMQ checks unknown transactions later;
5. a consumer processes committed messages idempotently.

The project intentionally uses the native RocketMQ client so the transaction boundary and checkback mechanism remain visible.

## Architecture

```text
POST /api/orders
      |
      v
TransactionMQProducer ---> RocketMQ half message
      |                           |
      v                           | check unknown state
H2 local transaction <------------+
  orders + local_transaction
      |
      | COMMIT only
      v
RocketMQ consumer ---> consumed_event (message-id deduplication)
```

Transactional messages provide eventual consistency between the producer's local transaction and message publication. They do not make downstream consumption part of the same database transaction; consumers still need retry-safe, idempotent processing.

## Requirements

- Java 17+
- Docker with Compose

Maven does not need to be installed; the repository includes Maven Wrapper.

## Run locally

Start RocketMQ and create a topic whose message type is `Transaction`:

```bash
docker compose up -d
docker compose ps
```

Start the application:

```bash
./mvnw spring-boot:run
```

The HTTP server listens on `http://localhost:8088`.

### 1. Commit immediately

```bash
curl -sS http://localhost:8088/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"productName":"Mechanical Keyboard","amount":399.00,"outcome":"COMMIT"}'
```

### 2. Roll back

```bash
curl -sS http://localhost:8088/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"productName":"Rollback Demo","amount":1.00,"outcome":"ROLLBACK"}'
```

No order row is created and the message is never visible to the consumer.

### 3. Trigger transaction checkback

```bash
curl -sS http://localhost:8088/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"productName":"Checkback Demo","amount":9.90,"outcome":"UNKNOWN"}'
```

The local database transaction commits, but the listener deliberately returns `UNKNOW`. RocketMQ later checks `local_transaction` and commits the half message.

Use the returned `orderId` to inspect local state:

```bash
curl -sS http://localhost:8088/api/orders/<orderId>
```

Watch the transaction and consumer logs:

```bash
docker compose logs -f broker
```

## Test and build

```bash
./mvnw verify
./mvnw package
docker build -t rocketmq-transaction-lab:local .
```

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `ROCKETMQ_NAME_SERVER` | `127.0.0.1:9876` | NameServer address |
| `ROCKETMQ_TOPIC` | `order-transaction-topic` | Transaction topic |
| `ROCKETMQ_PRODUCER_GROUP` | `order-transaction-producer` | Transaction producer group |
| `ROCKETMQ_CONSUMER_GROUP` | `order-created-consumer` | Consumer group |
| `ROCKETMQ_ENABLED` | `true` | Enables RocketMQ clients |

For a real system, replace H2 with a durable database, retain transaction evidence long enough for broker checkbacks, monitor unresolved transactions, and choose a consumer idempotency key based on the business event rather than only the RocketMQ message ID.

## License

Apache License 2.0. See [LICENSE](LICENSE).
