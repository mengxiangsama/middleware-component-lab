package io.github.middlewarelab.hikaricp;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderConnectionPoolTest {
    @Test
    void acquisitionTimesOutWhenFullAndSelectWorksAfterReturn() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setPoolName("orders-pool");
        config.setJdbcUrl("jdbc:h2:mem:orders_pool");
        config.setUsername("sa");
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(750);
        config.setValidationTimeout(500);

        try (var pool = new HikariDataSource(config)) {
            HikariPoolMXBean stats = pool.getHikariPoolMXBean();
            try (var first = pool.getConnection()) {
                insertOrder(first);

                try (var second = pool.getConnection()) {
                    assertEquals(2, stats.getActiveConnections());
                    assertEquals(0, stats.getIdleConnections());
                    long start = System.nanoTime();
                    assertThrows(SQLTransientConnectionException.class, () -> {
                        try (var unexpected = pool.getConnection()) {
                            // Close the third connection even if the assertion fails.
                        }
                    });
                    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    System.out.println("saturated: active=2 idle=0, next borrow timed out after "
                            + elapsedMs + " ms");
                }

                try (var recovered = pool.getConnection();
                     var query = recovered.prepareStatement("SELECT status FROM orders WHERE id = ?")) {
                    query.setInt(1, 1001);
                    try (var rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals("PAID", rows.getString("status"));
                        System.out.println("recovered: order 1001 returned PAID");
                    }
                }
            }
            assertEquals(0, stats.getActiveConnections());
        }
    }

    private void insertOrder(Connection connection) throws SQLException {
        try (var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, status VARCHAR(12) NOT NULL)");
            sql.executeUpdate("INSERT INTO orders (id, status) VALUES (1001, 'PAID')");
        }
    }
}
