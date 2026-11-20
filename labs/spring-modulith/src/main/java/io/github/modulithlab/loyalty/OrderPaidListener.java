package io.github.modulithlab.loyalty;

import io.github.modulithlab.orders.OrderPaid;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class OrderPaidListener {

    private static final String REWARD_TYPE = "ORDER_PAID";
    private static final int REWARD_POINTS = 10;

    private final JdbcTemplate jdbc;

    OrderPaidListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @ApplicationModuleListener(id = "loyalty.order-paid")
    void awardPoints(OrderPaid event) {
        try {
            jdbc.update("""
                    INSERT INTO loyalty_ledger (order_id, reward_type, customer_id, points)
                    VALUES (?, ?, ?, ?)
                    """, event.orderId(), REWARD_TYPE, event.customerId(), REWARD_POINTS);
        } catch (DuplicateKeyException alreadyAwarded) {
            // The ledger has one unique key: (order_id, reward_type).
            return;
        }

        jdbc.update("""
                UPDATE loyalty_account SET points = points + ? WHERE customer_id = ?
                """, REWARD_POINTS, event.customerId());
    }
}
