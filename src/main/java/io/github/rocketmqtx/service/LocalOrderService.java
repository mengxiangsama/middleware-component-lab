package io.github.rocketmqtx.service;

import io.github.rocketmqtx.domain.OrderCommand;
import io.github.rocketmqtx.domain.OrderView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocalOrderService {

    private final JdbcTemplate jdbcTemplate;

    public LocalOrderService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void createCommittedOrder(OrderCommand command) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "insert into orders(order_id, product_name, amount, status, created_at) values (?, ?, ?, ?, ?)",
                command.orderId(), command.productName(), command.amount(), "CREATED", Timestamp.from(now));
        jdbcTemplate.update(
                "insert into local_transaction(transaction_id, transaction_state, updated_at) values (?, ?, ?)",
                command.orderId(), "COMMITTED", Timestamp.from(now));
    }

    public boolean isCommitted(String transactionId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from local_transaction where transaction_id = ? and transaction_state = 'COMMITTED'",
                Integer.class,
                transactionId);
        return count != null && count > 0;
    }

    public Optional<OrderView> findOrder(String orderId) {
        List<OrderView> orders = jdbcTemplate.query(
                "select order_id, product_name, amount, status, created_at from orders where order_id = ?",
                (rs, rowNum) -> new OrderView(
                        rs.getString("order_id"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("amount"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()),
                orderId);
        return orders.stream().findFirst();
    }
}
