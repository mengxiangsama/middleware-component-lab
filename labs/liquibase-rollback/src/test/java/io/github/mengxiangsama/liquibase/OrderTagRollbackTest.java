package io.github.mengxiangsama.liquibase;

import liquibase.command.CommandScope;
import liquibase.exception.CommandExecutionException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderTagRollbackTest {
    private static final String DATABASE = "jdbc:h2:mem:orders_rollback";
    private static final String RELEASE_TAG = "orders-v1";

    @Test
    void rollsBackTheNewColumnAndKeepsTheExistingOrder() throws Exception {
        try (var connection = DriverManager.getConnection(DATABASE, "sa", "")) {
            command("updateToTag").addArgumentValue("tag", RELEASE_TAG).execute();
            assertEquals(List.of("orders-create", "orders-v1-tag"), appliedChanges(connection));
            assertEquals(RELEASE_TAG,
                    scalar(connection, "SELECT TAG FROM DATABASECHANGELOG WHERE ID = 'orders-v1-tag'"));
            assertFalse(hasDeliveryStatus(connection));

            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO orders (id, amount_cents) VALUES (42, 1200)");
            }

            command("update").execute();
            var appliedRelease = List.of("orders-create", "orders-v1-tag", "orders-add-delivery-status");
            assertEquals(appliedRelease, appliedChanges(connection));
            assertEquals("PENDING", deliveryStatus(connection));
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE orders SET delivery_status = 'SHIPPED' WHERE id = 42");
            }

            var rollbackSql = new ByteArrayOutputStream();
            command("rollbackSql")
                    .addArgumentValue("tag", RELEASE_TAG)
                    .setOutput(rollbackSql)
                    .execute();
            String preview = rollbackSql.toString(StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
            assertTrue(preview.contains("DROP COLUMN DELIVERY_STATUS"), preview);
            assertEquals(appliedRelease, appliedChanges(connection));
            assertEquals("SHIPPED", deliveryStatus(connection));

            command("rollback").addArgumentValue("tag", RELEASE_TAG).execute();
            assertEquals(List.of("orders-create"), appliedChanges(connection));
            assertFalse(hasDeliveryStatus(connection));
            assertEquals("1200", scalar(connection, "SELECT amount_cents FROM orders WHERE id = 42"));

            command("update").execute();
            assertEquals(appliedRelease, appliedChanges(connection));
            assertEquals("PENDING", deliveryStatus(connection));
            assertEquals("1200", scalar(connection, "SELECT amount_cents FROM orders WHERE id = 42"));

            command("update").execute();
            assertEquals(appliedRelease, appliedChanges(connection));
            assertEquals("1", scalar(connection, "SELECT COUNT(*) FROM orders"));
        }
    }

    private CommandScope command(String name) throws CommandExecutionException {
        return new CommandScope(name)
                .addArgumentValue("url", DATABASE)
                .addArgumentValue("username", "sa")
                .addArgumentValue("changelogFile", "orders.xml");
    }

    private List<String> appliedChanges(Connection connection) throws SQLException {
        var ids = new ArrayList<String>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT ID FROM DATABASECHANGELOG ORDER BY ORDEREXECUTED")) {
            while (rows.next()) {
                ids.add(rows.getString(1));
            }
        }
        return ids;
    }

    private boolean hasDeliveryStatus(Connection connection) throws SQLException {
        try (var columns = connection.getMetaData().getColumns(null, "PUBLIC", "ORDERS", "DELIVERY_STATUS")) {
            return columns.next();
        }
    }

    private String deliveryStatus(Connection connection) throws SQLException {
        return scalar(connection, "SELECT delivery_status FROM orders WHERE id = 42");
    }

    private String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            return rows.getString(1);
        }
    }
}
