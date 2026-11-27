package io.github.middlewarelab.debezium;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.debezium.embedded.Connect;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.debezium.engine.format.ChangeEventFormat;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipmentOutboxTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @Timeout(100)
    void routesCommittedOutboxRowsAndSkipsRolledBackWrites(@TempDir Path work) throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setServerConfig("wal_level", "logical").start();
             var writer = postgres.getPostgresDatabase().getConnection();
             var observer = postgres.getPostgresDatabase().getConnection()) {
            System.out.printf("PostgreSQL %s, localhost port %d%n",
                    observer.getMetaData().getDatabaseProductVersion(), postgres.getPort());
            createTablesAndPublication(writer);
            writeOrderAndOutbox(writer, observer, 100, "evt-ready", "PACKED", true);

            var records = new LinkedBlockingQueue<SourceRecord>();
            var completion = new AtomicReference<String>("engine still running");
            var executor = Executors.newSingleThreadExecutor();
            try (var engine = DebeziumEngine.create(ChangeEventFormat.of(Connect.class))
                    .using(settings(postgres.getPort(), work))
                    .using((success, message, error) -> completion.set(message + ", cause=" + error))
                    .notifying((RecordChangeEvent<SourceRecord> event) -> records.add(event.record()))
                    .build()) {
                executor.submit(engine);
                expectEvent(records, completion, 100, "evt-ready", "PACKED");

                writeOrderAndOutbox(writer, observer, 101, "evt-rollback", "PACKED", false);
                writeOrderAndOutbox(writer, observer, 102, "evt-commit", "PACKED", true);
                writeOrderAndOutbox(writer, observer, 103, "evt-barrier", "SHIPPED", true);

                expectEvent(records, completion, 102, "evt-commit", "PACKED");
                expectEvent(records, completion, 103, "evt-barrier", "SHIPPED");
                System.out.println("Outbox stream reached the committed barrier without an evt-rollback event");
            } finally {
                executor.shutdown();
                if (!executor.awaitTermination(15, SECONDS)) {
                    executor.shutdownNow();
                    throw new AssertionError("Debezium Engine did not stop");
                }
            }
        }
    }

    private static void createTablesAndPublication(Connection db) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.setQueryTimeout(15);
            statement.execute("""
                    CREATE TABLE public.shipment_order (
                        order_id INTEGER PRIMARY KEY, status TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE public.shipment_outbox (
                        id TEXT PRIMARY KEY,
                        aggregatetype TEXT NOT NULL,
                        aggregateid TEXT NOT NULL,
                        type TEXT NOT NULL,
                        payload JSONB NOT NULL
                    )
                    """);
            statement.execute("CREATE PUBLICATION shipment_outbox_pub FOR TABLE public.shipment_outbox");
        }
    }

    private static void writeOrderAndOutbox(Connection writer, Connection observer, int orderId,
                                            String eventId, String status, boolean commit) throws SQLException {
        writer.setAutoCommit(false);
        try (var order = writer.prepareStatement("INSERT INTO public.shipment_order VALUES (?, ?)");
             var event = writer.prepareStatement("""
                     INSERT INTO public.shipment_outbox (id, aggregatetype, aggregateid, type, payload)
                     VALUES (?, 'shipment', ?, ?, jsonb_build_object('orderId', ?::integer, 'status', ?::text))
                     """)) {
            order.setQueryTimeout(15);
            order.setInt(1, orderId);
            order.setString(2, status);
            assertEquals(1, order.executeUpdate());

            event.setQueryTimeout(15);
            event.setString(1, eventId);
            event.setString(2, Integer.toString(orderId));
            event.setString(3, status);
            event.setInt(4, orderId);
            event.setString(5, status);
            assertEquals(1, event.executeUpdate());

            expectCounts(observer, orderId, eventId, 0);
            if (commit) {
                writer.commit();
            }
        } finally {
            // Roll back any open transaction before resetting auto-commit, including on test failure.
            writer.rollback();
            writer.setAutoCommit(true);
        }
        expectCounts(observer, orderId, eventId, commit ? 1 : 0);
        System.out.printf("%s order=%d event=%s visible order/outbox rows=%s%n",
                commit ? "COMMIT" : "ROLLBACK", orderId, eventId, commit ? "1/1" : "0/0");
    }

    private static void expectCounts(Connection observer, int orderId, String eventId, int expected)
            throws SQLException {
        try (var query = observer.prepareStatement("""
                SELECT (SELECT count(*) FROM public.shipment_order WHERE order_id = ?),
                       (SELECT count(*) FROM public.shipment_outbox WHERE id = ?)
                """)) {
            query.setQueryTimeout(15);
            query.setInt(1, orderId);
            query.setString(2, eventId);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(expected, rows.getInt(1), "order row count for " + orderId);
                assertEquals(expected, rows.getInt(2), "outbox row count for " + eventId);
            }
        }
    }

    private static void expectEvent(BlockingQueue<SourceRecord> records, AtomicReference<String> completion,
                                    int orderId, String eventId, String status) throws Exception {
        var event = records.poll(25, SECONDS);
        assertNotNull(event, () -> "Missing event " + eventId + ": " + completion.get());
        assertEquals("outbox.event.shipment", event.topic());
        assertEquals(Integer.toString(orderId), event.key());
        var idHeader = event.headers().lastWithName("id");
        var typeHeader = event.headers().lastWithName("eventType");
        assertNotNull(idHeader);
        assertNotNull(typeHeader);
        assertEquals(eventId, idHeader.value());
        assertEquals(status, typeHeader.value());
        var payload = JSON.readTree(assertInstanceOf(String.class, event.value()));
        assertEquals(orderId, payload.path("orderId").asInt());
        assertEquals(status, payload.path("status").asText());
        System.out.printf("Routed %s key=%s id=%s eventType=%s payload=%s%n",
                event.topic(), event.key(), idHeader.value(), typeHeader.value(), payload);
    }

    private static Properties settings(int port, Path work) {
        var properties = new Properties();
        properties.setProperty("name", "shipment-outbox-lab");
        properties.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        properties.setProperty("database.hostname", "127.0.0.1");
        properties.setProperty("database.port", Integer.toString(port));
        properties.setProperty("database.user", "postgres");
        properties.setProperty("database.dbname", "postgres");
        properties.setProperty("topic.prefix", "shipment_lab");
        properties.setProperty("plugin.name", "pgoutput");
        properties.setProperty("table.include.list", "public.shipment_outbox");
        properties.setProperty("slot.name", "shipment_outbox_slot");
        properties.setProperty("publication.name", "shipment_outbox_pub");
        properties.setProperty("publication.autocreate.mode", "disabled");
        properties.setProperty("snapshot.mode", "initial");
        properties.setProperty("tombstones.on.delete", "false");
        properties.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
        properties.setProperty("offset.storage.file.filename", work.resolve("offsets.dat").toString());
        properties.setProperty("offset.flush.interval.ms", "0");
        properties.setProperty("record.processing.order", "ORDERED");
        properties.setProperty("transforms", "outbox");
        properties.setProperty("transforms.outbox.type", "io.debezium.transforms.outbox.EventRouter");
        properties.setProperty("transforms.outbox.table.fields.additional.placement", "type:header:eventType");
        return properties;
    }
}
