package io.github.modulithlab;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import io.github.modulithlab.orders.OrderPaid;
import io.github.modulithlab.orders.OrderPayment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:modulith-test")
@Import(OrderPaidDeliveryTest.TestDelivery.class)
class OrderPaidDeliveryTest {

    private static final String ORDER_ID = "order-1";
    private static final String CUSTOMER_ID = "customer-1";
    private static final String LISTENER_ID = "loyalty.order-paid";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OrderPayment orders;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    IncompleteEventPublications incomplete;

    @Autowired
    TestDelivery delivery;

    @BeforeEach
    void setUp() {
        delivery.failures.clear();
        jdbc.update("DELETE FROM event_publication");
        jdbc.update("DELETE FROM loyalty_ledger");
        jdbc.update("DELETE FROM loyalty_account");
        jdbc.update("DELETE FROM shop_order");
        jdbc.update("INSERT INTO loyalty_account (customer_id) VALUES (?)", CUSTOMER_ID);
        jdbc.update("INSERT INTO shop_order (order_id, customer_id, status) VALUES (?, ?, 'PENDING')",
                ORDER_ID, CUSTOMER_ID);
    }

    @Test
    void verifiesModuleBoundaries() {
        ApplicationModules.of(ModulithEventLabApplication.class).verify();
    }

    @Test
    void replaysFailedDeliveryAndAwardsPointsOnlyOnce() {
        // Force the balance update to fail after the ledger insert.
        jdbc.execute("ALTER TABLE loyalty_account ADD CONSTRAINT test_fail_first_award CHECK (points = 0)");
        try {
            assertThat(orders.pay(ORDER_ID)).isTrue();
        } finally {
            jdbc.execute("ALTER TABLE loyalty_account DROP CONSTRAINT test_fail_first_award");
        }

        assertThat(delivery.failures).singleElement().satisfies(failure -> assertThat(failure)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("TEST_FAIL_FIRST_AWARD"));
        assertThat(orderStatus()).isEqualTo("PAID");
        assertPoints(0, 0);
        List<Publication> failed = publications();
        assertThat(failed).singleElement().satisfies(publication -> assertThat(publication.completed()).isFalse());

        UUID originalId = failed.get(0).id();
        incomplete.resubmitIncompletePublications(publication -> publication.getIdentifier().equals(originalId));

        assertThat(orderStatus()).isEqualTo("PAID");
        assertPoints(10, 1);
        assertThat(jdbc.queryForObject("""
                SELECT points FROM loyalty_ledger WHERE order_id = ? AND reward_type = ? AND customer_id = ?
                """, Integer.class, ORDER_ID, "ORDER_PAID", CUSTOMER_ID)).isEqualTo(10);
        assertThat(publications()).containsExactly(new Publication(originalId, true));

        // Publish a distinct event for the same order, as a redelivery might do.
        transactions.executeWithoutResult(status -> events.publishEvent(new OrderPaid(ORDER_ID, CUSTOMER_ID)));

        assertPoints(10, 1);
        assertThat(publications()).hasSize(2)
                .contains(new Publication(originalId, true))
                .allMatch(Publication::completed);
        assertThat(delivery.failures).hasSize(1);
    }

    @Test
    void rollingBackPaymentAlsoRollsBackItsPublication() {
        transactions.executeWithoutResult(status -> {
            assertThat(orders.pay(ORDER_ID)).isTrue();
            assertThat(orderStatus()).isEqualTo("PAID");
            assertThat(publications()).singleElement()
                    .satisfies(publication -> assertThat(publication.completed()).isFalse());
            status.setRollbackOnly();
        });

        assertThat(orderStatus()).isEqualTo("PENDING");
        assertThat(publications()).isEmpty();
        assertPoints(0, 0);
        assertThat(delivery.failures).isEmpty();
    }

    private String orderStatus() {
        return jdbc.queryForObject("SELECT status FROM shop_order WHERE order_id = ?", String.class, ORDER_ID);
    }

    private void assertPoints(int balance, int ledgerEntries) {
        assertThat(jdbc.queryForObject("SELECT points FROM loyalty_account WHERE customer_id = ?",
                Integer.class, CUSTOMER_ID)).isEqualTo(balance);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM loyalty_ledger", Integer.class))
                .isEqualTo(ledgerEntries);
    }

    private List<Publication> publications() {
        return jdbc.query("SELECT id, completion_date FROM event_publication WHERE listener_id = ?",
                (row, index) -> new Publication(row.getObject("id", UUID.class),
                        row.getObject("completion_date") != null),
                LISTENER_ID);
    }

    private record Publication(UUID id, boolean completed) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDelivery implements AsyncConfigurer {

        private final List<Throwable> failures = new ArrayList<>();

        @Override
        public Executor getAsyncExecutor() {
            return new SyncTaskExecutor();
        }

        @Override
        public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
            return (failure, method, arguments) -> failures.add(failure);
        }
    }
}
