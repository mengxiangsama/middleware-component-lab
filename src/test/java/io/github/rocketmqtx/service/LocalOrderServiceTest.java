package io.github.rocketmqtx.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rocketmqtx.domain.OrderCommand;
import io.github.rocketmqtx.domain.TransactionOutcome;
import java.math.BigDecimal;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LocalOrderServiceTest {

    private LocalOrderService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:orders;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("drop all objects");
        jdbcTemplate.execute("create table orders (order_id varchar(64) primary key, product_name varchar(200), amount decimal(18,2), status varchar(32), created_at timestamp)");
        jdbcTemplate.execute("create table local_transaction (transaction_id varchar(64) primary key, transaction_state varchar(32), updated_at timestamp)");
        service = new LocalOrderService(jdbcTemplate);
    }

    @Test
    void persistsOrderAndTransactionEvidence() {
        OrderCommand command = new OrderCommand(
                "order-42", "Mouse", new BigDecimal("39.90"), TransactionOutcome.COMMIT);

        service.createCommittedOrder(command);

        assertThat(service.isCommitted("order-42")).isTrue();
        assertThat(service.findOrder("order-42")).get()
                .extracting(order -> order.productName())
                .isEqualTo("Mouse");
    }

    @Test
    void returnsEmptyForMissingOrder() {
        assertThat(service.findOrder("missing")).isEmpty();
        assertThat(service.isCommitted("missing")).isFalse();
    }
}
