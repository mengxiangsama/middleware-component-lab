package io.github.middlewarelab.pulsar;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.KeySharedPolicy;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class AutoSplitHandoffTest {
    private static final String KEY = "order-2001";
    private static final String FIRST_EVENT = "PACKED";
    private static final String FOLLOW_UP = "SHIPPED";

    @Test
    @Timeout(75)
    void remainingWorkerReceivesUnacknowledgedMessageAfterOwnerProcessExits(@TempDir Path exchange) throws Exception {
        String url = System.getenv("PULSAR_LAB_URL");
        assertNotNull(url, "Run sh labs/pulsar-key-shared/verify.sh to start the lab broker");
        String topic = "persistent://public/default/auto-split-handoff-" + UUID.randomUUID();
        Path workerLog = exchange.resolve("worker-a.log");
        var workerA = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                WorkerProcess.class.getName(), url, topic, exchange.toString())
                .redirectErrorStream(true)
                .redirectOutput(workerLog.toFile())
                .start();

        try (var parentClient = client(url);
             var producer = parentClient.newProducer(Schema.STRING).topic(topic).enableBatching(false).create()) {
            awaitMarker(exchange.resolve("ready"), workerA, workerLog);
            var firstId = producer.newMessage().key(KEY).value(FIRST_EVENT).send();
            Path receipt = awaitMarker(exchange.resolve("received-id"), workerA, workerLog);
            assertArrayEquals(firstId.toByteArray(), Files.readAllBytes(receipt));

            try (var workerB = consumer(parentClient, topic, "worker-b")) {
                assertNull(workerB.receive(300, TimeUnit.MILLISECONDS), "B received a message while A was still active");
                workerA.destroyForcibly();
                assertTrue(workerA.waitFor(10, TimeUnit.SECONDS), "A's separate client process did not exit");

                var redelivery = workerB.receive(15, TimeUnit.SECONDS);
                assertNotNull(redelivery, "B did not receive A's unacknowledged message");
                assertArrayEquals(firstId.toByteArray(), redelivery.getMessageId().toByteArray());
                assertEquals(KEY, redelivery.getKey());
                assertEquals(FIRST_EVENT, redelivery.getValue());
                workerB.acknowledge(redelivery);

                var nextId = producer.newMessage().key(KEY).value(FOLLOW_UP).send();
                assertNotEquals(firstId, nextId);
                var followUp = workerB.receive(10, TimeUnit.SECONDS);
                assertNotNull(followUp, "B did not receive the same-key follow-up");
                assertArrayEquals(nextId.toByteArray(), followUp.getMessageId().toByteArray());
                assertEquals(KEY, followUp.getKey());
                assertEquals(FOLLOW_UP, followUp.getValue());
                workerB.acknowledge(followUp);

                System.out.printf("AUTO_SPLIT %s: A received %s (ID %s) without ACK. "
                                + "After A exited, B received the same ID and ACKed it, then received %s (ID %s).%n",
                        KEY, FIRST_EVENT, firstId, FOLLOW_UP, nextId);
            }
        } finally {
            if (workerA.isAlive()) {
                workerA.destroyForcibly();
                assertTrue(workerA.waitFor(10, TimeUnit.SECONDS), "A's test JVM could not be stopped");
            }
        }
    }

    private static Path awaitMarker(Path marker, Process worker, Path log) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(marker) && worker.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(30);
        }
        if (!Files.exists(marker) || !worker.isAlive()) {
            fail("A did not reach " + marker.getFileName() + ":\n" + Files.readString(log));
        }
        return marker;
    }

    private static PulsarClient client(String url) throws PulsarClientException {
        return PulsarClient.builder().serviceUrl(url).ioThreads(1).listenerThreads(1)
                .operationTimeout(10, TimeUnit.SECONDS).build();
    }

    private static Consumer<String> consumer(PulsarClient client, String topic, String name)
            throws PulsarClientException {
        return client.newConsumer(Schema.STRING)
                .topic(topic)
                .subscriptionName("shipment-handoff")
                .subscriptionType(SubscriptionType.Key_Shared)
                .keySharedPolicy(KeySharedPolicy.autoSplitHashRange())
                .consumerName(name)
                .acknowledgmentGroupTime(0, TimeUnit.MILLISECONDS)
                .isAckReceiptEnabled(true)
                .subscribe();
    }

    public static class WorkerProcess {
        public static void main(String[] args) throws Exception {
            Path exchange = Path.of(args[2]);
            try (var client = client(args[0]); var workerA = consumer(client, args[1], "worker-a")) {
                Files.createFile(exchange.resolve("ready"));
                var message = workerA.receive(15, TimeUnit.SECONDS);
                assertNotNull(message, "A did not receive the first event");
                assertEquals(KEY, message.getKey());
                assertEquals(FIRST_EVENT, message.getValue());
                Path receipt = Files.write(exchange.resolve("received.tmp"), message.getMessageId().toByteArray());
                Files.move(receipt, exchange.resolve("received-id"), StandardCopyOption.ATOMIC_MOVE);
                // Hold the connection and leave the message unacknowledged until the parent kills this JVM.
                System.in.read();
            }
        }
    }
}
