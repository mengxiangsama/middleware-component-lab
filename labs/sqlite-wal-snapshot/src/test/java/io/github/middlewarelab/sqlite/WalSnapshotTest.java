package io.github.middlewarelab.sqlite;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sqlite.SQLiteErrorCode.SQLITE_BUSY;
import static org.sqlite.SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT;

class WalSnapshotTest {
    @TempDir
    Path directory;

    @Test
    @Timeout(30)
    void reportHoldsSnapshotWhileShipmentWriterCommits() throws SQLException {
        String url = "jdbc:sqlite:" + directory.resolve("shipments.db");
        try (var writer = DriverManager.getConnection(url);
             var report = DriverManager.getConnection(url);
             var observer = DriverManager.getConnection(url);
             var writes = writer.createStatement();
             var reports = report.createStatement();
             var observations = observer.createStatement()) {
            try (var mode = writes.executeQuery("PRAGMA journal_mode = WAL")) {
                assertTrue(mode.next());
                assertEquals("wal", mode.getString(1));
            }
            observations.execute("PRAGMA busy_timeout = 0");
            reports.execute("PRAGMA busy_timeout = 0");
            writes.execute("CREATE TABLE shipments (order_id INTEGER PRIMARY KEY, cost_cents INTEGER NOT NULL)");
            writes.executeUpdate("INSERT INTO shipments VALUES (101, 1200)");

            reports.execute("BEGIN");
            assertEquals(1200, shipmentCost(reports));

            writes.execute("BEGIN IMMEDIATE");
            writes.executeUpdate("INSERT INTO shipments VALUES (102, 800)");
            assertEquals(1200, shipmentCost(observations), "Uncommitted shipments must stay invisible");
            var secondWriter = assertThrows(SQLiteException.class,
                    () -> observations.execute("BEGIN IMMEDIATE"));
            assertEquals(SQLITE_BUSY, secondWriter.getResultCode());

            writes.execute("COMMIT");
            assertEquals(2000, shipmentCost(observations), "A new read must see order 102");
            assertEquals(1200, shipmentCost(reports), "The open report keeps its original snapshot");
            var staleSnapshot = assertThrows(SQLiteException.class,
                    () -> reports.executeUpdate("INSERT INTO shipments VALUES (103, 500)"));
            assertEquals(SQLITE_BUSY_SNAPSHOT, staleSnapshot.getResultCode());

            reports.execute("ROLLBACK");
            assertEquals(2000, shipmentCost(reports), "After ending the snapshot the same connection reads fresh data");
            try (var version = reports.executeQuery("SELECT sqlite_version()")) {
                assertTrue(version.next());
                System.out.printf("SQLite %s WAL: report 1200 -> 1200 -> 2000 cents, second writer %s, stale snapshot %s%n",
                        version.getString(1), secondWriter.getResultCode().name(), staleSnapshot.getResultCode().name());
            }
        }
    }

    private static long shipmentCost(Statement statement) throws SQLException {
        try (var result = statement.executeQuery("SELECT sum(cost_cents) FROM shipments")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}
