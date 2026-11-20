package io.github.modulithlab.orders;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderPayment {

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public OrderPayment(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional
    public boolean pay(String orderId) {
        int updated = jdbc.update("""
                UPDATE shop_order SET status = 'PAID' WHERE order_id = ? AND status = 'PENDING'
                """, orderId);
        if (updated == 0) {
            return false;
        }

        String customerId = jdbc.queryForObject("""
                SELECT customer_id FROM shop_order WHERE order_id = ?
                """, String.class, orderId);
        events.publishEvent(new OrderPaid(orderId, customerId));
        return true;
    }
}
