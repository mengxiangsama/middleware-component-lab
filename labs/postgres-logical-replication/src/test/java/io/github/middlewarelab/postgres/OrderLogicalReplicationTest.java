package io.github.middlewarelab.postgres;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderLogicalReplicationTest {
    private static final String SCHEMA = """
            CREATE TABLE public.demo_orders (
                order_id INTEGER PRIMARY KEY,
                status TEXT NOT NULL
            )
            """;
    private static final String ORDERS =
            "SELECT order_id || ':' || status FROM public.demo_orders ORDER BY order_id";
    private static final String SLOT_ACTIVE = """
            SELECT active::text FROM pg_replication_slots
            WHERE slot_name = 'demo_orders_sub' AND plugin = 'pgoutput'
            """;

    @Test
    void copiesExistingRowsReplicatesChangesAndCatchesUpAfterPause() throws Exception {
        try (var publisher = EmbeddedPostgres.builder()
                     .setServerConfig("wal_level", "logical").start();
             var subscriber = EmbeddedPostgres.builder().start();
             var source = publisher.getPostgresDatabase().getConnection();
             var target = subscriber.getPostgresDatabase().getConnection()) {
            System.out.println("PostgreSQL: " + query(source, "SHOW server_version").get(0));

            execute(source, SCHEMA);
            execute(target, SCHEMA);
            execute(source, "INSERT INTO public.demo_orders VALUES (101, 'NEW'), (102, 'NEW')");
            execute(source, "CREATE PUBLICATION demo_orders_pub FOR TABLE public.demo_orders");
            execute(target, """
                    CREATE SUBSCRIPTION demo_orders_sub
                    CONNECTION 'host=127.0.0.1 port=%d dbname=postgres user=postgres connect_timeout=5'
                    PUBLICATION demo_orders_pub
                    """.formatted(publisher.getPort()));

            awaitRows(target, ORDERS, List.of("101:NEW", "102:NEW"));
            awaitRows(source, SLOT_ACTIVE, List.of("true"));
            System.out.println("initial copy: 101:NEW, 102:NEW");

            source.setAutoCommit(false);
            execute(source, "UPDATE public.demo_orders SET status = 'PAID' WHERE order_id = 101");
            execute(source, "DELETE FROM public.demo_orders WHERE order_id = 102");
            execute(source, "INSERT INTO public.demo_orders VALUES (103, 'NEW')");
            source.commit();
            source.setAutoCommit(true);

            var afterChanges = List.of("101:PAID", "103:NEW");
            awaitRows(target, ORDERS, afterChanges);
            System.out.println("incremental INSERT, UPDATE, DELETE: 101:PAID, 103:NEW");

            execute(target, "ALTER SUBSCRIPTION demo_orders_sub DISABLE");
            awaitRows(source, SLOT_ACTIVE, List.of("false"));
            execute(source, "INSERT INTO public.demo_orders VALUES (104, 'NEW')");
            assertEquals(afterChanges, query(target, ORDERS));
            execute(target, "ALTER SUBSCRIPTION demo_orders_sub ENABLE");
            awaitRows(target, ORDERS, List.of("101:PAID", "103:NEW", "104:NEW"));
            System.out.println("resume: applied order 104 from the same replication slot");

            execute(target, "DROP SUBSCRIPTION demo_orders_sub");
            awaitRows(source, SLOT_ACTIVE, List.of());
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            statement.execute(sql);
        }
    }

    private static List<String> query(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            try (var rows = statement.executeQuery(sql)) {
                var values = new ArrayList<String>();
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
                return values;
            }
        }
    }

    private static void awaitRows(Connection connection, String sql, List<String> expected)
            throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        List<String> observed;
        do {
            observed = query(connection, sql);
            if (expected.equals(observed)) {
                return;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertEquals(expected, observed, sql);
    }
}
