package io.github.middlewarelab.mysql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobClaimTest {
    @Test
    @Timeout(30)
    void workersTakeDifferentJobsAndRollbackReleasesClaim() throws SQLException {
        String url = "jdbc:mysql://127.0.0.1:" + System.getenv("MYSQL_QUEUE_PORT")
                + "/shipment_lab?sslMode=REQUIRED&connectTimeout=3000&socketTimeout=5000";
        String password = System.getenv("MYSQL_QUEUE_PASSWORD");

        try (var inspector = DriverManager.getConnection(url, "queue_lab", password);
             var workerA = DriverManager.getConnection(url, "queue_lab", password);
             var workerB = DriverManager.getConnection(url, "queue_lab", password);
             var workerC = DriverManager.getConnection(url, "queue_lab", password)) {
            try (var schema = inspector.createStatement()) {
                schema.executeUpdate("""
                        CREATE TABLE shipment_jobs (
                            id BIGINT PRIMARY KEY,
                            status VARCHAR(12) NOT NULL DEFAULT 'READY',
                            worker VARCHAR(12),
                            INDEX ready_order (status, id)
                        ) ENGINE=InnoDB
                        """);
                schema.executeUpdate("INSERT INTO shipment_jobs (id) VALUES (101), (102)");
            }

            begin(workerA);
            assertEquals(101, claim(workerA, "A").orElseThrow());

            begin(workerB);
            assertEquals(102, claim(workerB, "B").orElseThrow());
            workerB.commit();

            workerA.rollback();
            begin(workerC);
            assertEquals(101, claim(workerC, "C").orElseThrow());
            workerC.commit();
            assertTrue(claim(workerC, "C").isEmpty());
            workerC.rollback();

            var committed = new ArrayList<String>();
            try (var query = inspector.createStatement();
                 var rows = query.executeQuery("SELECT id, status, worker FROM shipment_jobs ORDER BY id")) {
                while (rows.next()) {
                    committed.add(rows.getLong(1) + "/" + rows.getString(2) + "/" + rows.getString(3));
                }
            }
            assertEquals(List.of("101/CLAIMED/C", "102/CLAIMED/B"), committed);
            System.out.printf("MySQL %s: A held 101, B skipped to 102, C recovered 101 after rollback: %s%n",
                    inspector.getMetaData().getDatabaseProductVersion(), committed);
        }
    }

    private static void begin(Connection connection) throws SQLException {
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setAutoCommit(false);
    }

    private static OptionalLong claim(Connection connection, String worker) throws SQLException {
        long id;
        try (var query = connection.createStatement();
             var row = query.executeQuery("""
                     SELECT id FROM shipment_jobs
                     WHERE status = 'READY'
                     ORDER BY id
                     LIMIT 1 FOR UPDATE SKIP LOCKED
                     """)) {
            if (!row.next()) {
                return OptionalLong.empty();
            }
            id = row.getLong(1);
        }
        try (var update = connection.prepareStatement(
                "UPDATE shipment_jobs SET status = 'CLAIMED', worker = ? WHERE id = ?")) {
            update.setString(1, worker);
            update.setLong(2, id);
            assertEquals(1, update.executeUpdate());
        }
        return OptionalLong.of(id);
    }
}
