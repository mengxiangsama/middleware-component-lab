package io.github.middlewarelab.debezium;

import io.debezium.embedded.Connect;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.debezium.engine.format.ChangeEventFormat;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OrderCdcTest {
    @TempDir
    Path work;

    @Test
    void readsInitialSnapshotAndCommittedRowChanges() throws Exception {
        try (var postgres = EmbeddedPostgres.builder()
                     .setServerConfig("wal_level", "logical").start();
             var db = postgres.getPostgresDatabase().getConnection()) {
            execute(db, "CREATE TABLE public.cdc_orders (order_id INTEGER PRIMARY KEY, status TEXT NOT NULL)");
            execute(db, "ALTER TABLE public.cdc_orders REPLICA IDENTITY FULL");
            execute(db, "INSERT INTO public.cdc_orders VALUES (41, 'PAID')");
            execute(db, "CREATE PUBLICATION orders_lab_pub FOR TABLE public.cdc_orders");

            var records = new LinkedBlockingQueue<SourceRecord>();
            var completion = new AtomicReference<String>("engine still running");
            var executor = Executors.newSingleThreadExecutor();
            try (var engine = DebeziumEngine.create(ChangeEventFormat.of(Connect.class))
                    .using(settings(postgres.getPort()))
                    .using((success, message, error) -> completion.set(message + ", cause=" + error))
                    .notifying((RecordChangeEvent<SourceRecord> event) -> records.add(event.record()))
                    .build()) {
                executor.submit(engine);
                expect(records, completion, "r", 41, null, "PAID");

                execute(db, "INSERT INTO public.cdc_orders VALUES (42, 'NEW')");
                expect(records, completion, "c", 42, null, "NEW");
                execute(db, "UPDATE public.cdc_orders SET status = 'PAID' WHERE order_id = 42");
                expect(records, completion, "u", 42, "NEW", "PAID");
                execute(db, "DELETE FROM public.cdc_orders WHERE order_id = 42");
                expect(records, completion, "d", 42, "PAID", null);
            } finally {
                executor.shutdown();
                if (!executor.awaitTermination(15, SECONDS)) {
                    executor.shutdownNow();
                    throw new AssertionError("Debezium Engine did not stop");
                }
            }
        }
    }

    private Properties settings(int port) {
        var properties = new Properties();
        properties.setProperty("name", "orders-cdc-lab");
        properties.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        properties.setProperty("database.hostname", "127.0.0.1");
        properties.setProperty("database.port", Integer.toString(port));
        properties.setProperty("database.user", "postgres");
        properties.setProperty("database.dbname", "postgres");
        properties.setProperty("topic.prefix", "orders_lab");
        properties.setProperty("plugin.name", "pgoutput");
        properties.setProperty("table.include.list", "public.cdc_orders");
        properties.setProperty("slot.name", "orders_lab_slot");
        properties.setProperty("publication.name", "orders_lab_pub");
        properties.setProperty("publication.autocreate.mode", "disabled");
        properties.setProperty("snapshot.mode", "initial");
        properties.setProperty("tombstones.on.delete", "false");
        properties.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
        properties.setProperty("offset.storage.file.filename", work.resolve("offsets.dat").toString());
        properties.setProperty("offset.flush.interval.ms", "0");
        return properties;
    }

    private static void execute(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.setQueryTimeout(15);
            statement.execute(sql);
        }
    }

    private static void expect(BlockingQueue<SourceRecord> records, AtomicReference<String> completion,
                               String operation, int id, String before, String after)
            throws InterruptedException {
        var event = records.poll(30, SECONDS);
        assertNotNull(event, () -> "Missing operation " + operation + ": " + completion.get());
        assertEquals("orders_lab.public.cdc_orders", event.topic());
        assertEquals(id, ((Struct) event.key()).getInt32("order_id"));
        var envelope = (Struct) event.value();
        assertEquals(operation, envelope.getString("op"));
        assertEquals(before, status(envelope.getStruct("before"), id));
        assertEquals(after, status(envelope.getStruct("after"), id));
        System.out.printf("%s order=%d before=%s after=%s%n", operation, id, before, after);
    }

    private static String status(Struct row, int id) {
        if (row == null) {
            return null;
        }
        assertEquals(id, row.getInt32("order_id"));
        return row.getString("status");
    }
}
