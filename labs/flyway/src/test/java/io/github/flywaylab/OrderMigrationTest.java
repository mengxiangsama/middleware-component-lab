package io.github.flywaylab;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderMigrationTest {
    @TempDir
    Path workspace;

    @Test
    void upgradesExistingOrderAndRejectsChangedMigration() throws IOException, SQLException {
        Path migrations = Files.createDirectory(workspace.resolve("migration"));
        copyMigration("V1__create_orders.sql", migrations);
        Path v2 = copyMigration("V2__add_order_status.sql", migrations);

        String url = "jdbc:h2:file:" + workspace.resolve("orders");
        var config = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("filesystem:" + migrations);

        assertEquals(1, config.target(MigrationVersion.fromVersion("1")).load().migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO orders (id, amount) VALUES (101, 19.90)");
        }

        Flyway flyway = config.target(MigrationVersion.LATEST).load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals("2", flyway.info().current().getVersion().getVersion());
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var order = statement.executeQuery("SELECT id, amount, status FROM orders")) {
            assertTrue(order.next());
            assertEquals(101, order.getLong("id"));
            assertEquals(new BigDecimal("19.90"), order.getBigDecimal("amount"));
            assertEquals("PENDING", order.getString("status"));
            assertFalse(order.next());
        }

        String original = Files.readString(v2);
        Files.writeString(v2, original.replace("'PENDING'", "'PAID'"));
        assertThrows(FlywayValidateException.class, flyway::validate);
        var result = flyway.validateWithResult();
        assertFalse(result.validationSuccessful);
        var invalidMigrations = result.invalidMigrations;
        assertEquals(1, invalidMigrations.size());
        assertEquals("2", invalidMigrations.get(0).version);
        assertEquals(CoreErrorCode.CHECKSUM_MISMATCH, invalidMigrations.get(0).errorDetails.errorCode);
        System.out.println("V2: CHECKSUM_MISMATCH");
        Files.writeString(v2, original);
        flyway.validate();
    }

    private Path copyMigration(String name, Path destination) throws IOException {
        Path target = destination.resolve(name);
        try (InputStream sql = getClass().getResourceAsStream("/db/migration/" + name)) {
            Files.copy(sql, target);
        }
        return target;
    }
}
