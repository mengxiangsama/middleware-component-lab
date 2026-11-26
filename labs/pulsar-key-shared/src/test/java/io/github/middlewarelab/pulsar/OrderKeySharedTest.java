package io.github.middlewarelab.pulsar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.KeySharedPolicy;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Range;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderKeySharedTest {
    @Test
    @Timeout(60)
    void workersShareOrdersAndKeepEachOrderOnOneWorker() throws PulsarClientException {
        String url = System.getenv("PULSAR_LAB_URL");
        assertNotNull(url, "Run sh labs/pulsar-key-shared/verify.sh to start the lab broker");
        String topic = "persistent://public/default/key-shared-orders-" + UUID.randomUUID();
        List<String> orders = IntStream.rangeClosed(1001, 1016).mapToObj(id -> "order-" + id).toList();
        List<String> stages = List.of("CREATED", "PAID", "SHIPPED");

        try (var client = PulsarClient.builder().serviceUrl(url).ioThreads(1).listenerThreads(1)
                .operationTimeout(10, TimeUnit.SECONDS).build();
             var workerA = worker(client, topic, "worker-a", Range.of(0, 32767));
             var workerB = worker(client, topic, "worker-b", Range.of(32768, 65535));
             var producer = client.newProducer(Schema.STRING).topic(topic).enableBatching(false).create()) {
            for (String stage : stages) {
                for (String order : orders) {
                    producer.newMessage().key(order).value(stage).send();
                }
            }

            var owners = new HashMap<String, String>();
            var deliveries = new HashMap<String, List<String>>();
            int expectedMessages = orders.size() * stages.size();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            int received = 0;
            while (received < expectedMessages && System.nanoTime() < deadline) {
                received += receiveOne(workerA, owners, deliveries);
                received += receiveOne(workerB, owners, deliveries);
            }

            assertEquals(expectedMessages, received);
            assertEquals(new HashSet<>(orders), deliveries.keySet());
            for (String order : orders) {
                assertEquals(stages, deliveries.get(order), "Delivery order for " + order);
            }
            assertEquals("worker-a", owners.get("order-1002"));
            assertEquals("worker-b", owners.get("order-1001"));
            long aOrders = owners.values().stream().filter("worker-a"::equals).count();
            long bOrders = owners.values().stream().filter("worker-b"::equals).count();
            System.out.printf("Delivered %d messages. worker-a=%d orders, worker-b=%d orders.%n",
                    received, aOrders, bOrders);
            System.out.printf("All %d orders stayed with one worker in %s order.%n", orders.size(), stages);
        }
    }

    private static Consumer<String> worker(PulsarClient client, String topic, String name, Range range)
            throws PulsarClientException {
        return client.newConsumer(Schema.STRING)
                .topic(topic)
                .subscriptionName("shipment-workers")
                .subscriptionType(SubscriptionType.Key_Shared)
                .keySharedPolicy(KeySharedPolicy.stickyHashRange().ranges(range))
                .consumerName(name)
                .acknowledgmentGroupTime(0, TimeUnit.MILLISECONDS)
                .subscribe();
    }

    private static int receiveOne(Consumer<String> worker, Map<String, String> owners,
                                  Map<String, List<String>> deliveries) throws PulsarClientException {
        var message = worker.receive(150, TimeUnit.MILLISECONDS);
        if (message == null) {
            return 0;
        }
        assertTrue(message.hasKey());
        String key = message.getKey();
        String name = worker.getConsumerName();
        assertEquals(name, owners.computeIfAbsent(key, ignored -> name), "Changed owner for " + key);
        deliveries.computeIfAbsent(key, ignored -> new ArrayList<>()).add(message.getValue());
        worker.acknowledge(message);
        return 1;
    }
}
