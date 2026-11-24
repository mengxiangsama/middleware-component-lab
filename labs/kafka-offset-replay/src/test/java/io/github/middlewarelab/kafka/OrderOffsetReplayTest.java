package io.github.middlewarelab.kafka;

import kafka.server.KafkaConfig;
import kafka.server.KafkaRaftServer;
import kafka.tools.StorageTool;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Time;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderOffsetReplayTest {
    private static final String TOPIC = "orders.shipped";
    private static final String LIVE_GROUP = "shipping-live";
    private static final String REPLAY_GROUP = "shipping-rebuild";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    Path temporaryDirectory;

    @Test
    void savedOffsetResumesAndSeparateReplayKeepsItUnchanged() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        int brokerPort;
        int controllerPort;
        try (var broker = new ServerSocket(0, 1, loopback);
             var controller = new ServerSocket(0, 1, loopback)) {
            brokerPort = broker.getLocalPort();
            controllerPort = controller.getLocalPort();
        }
        String bootstrap = "127.0.0.1:" + brokerPort;

        Properties properties = new Properties();
        properties.setProperty("process.roles", "broker,controller");
        properties.setProperty("node.id", "1");
        properties.setProperty("controller.quorum.bootstrap.servers", "127.0.0.1:" + controllerPort);
        properties.setProperty("listeners", "PLAINTEXT://" + bootstrap + ",CONTROLLER://127.0.0.1:" + controllerPort);
        properties.setProperty("advertised.listeners", "PLAINTEXT://" + bootstrap);
        properties.setProperty("controller.listener.names", "CONTROLLER");
        properties.setProperty("inter.broker.listener.name", "PLAINTEXT");
        properties.setProperty("listener.security.protocol.map", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT");
        properties.setProperty("log.dirs", temporaryDirectory.resolve("broker").toString());
        properties.setProperty("offsets.topic.replication.factor", "1");
        properties.setProperty("offsets.topic.num.partitions", "1");
        properties.setProperty("group.initial.rebalance.delay.ms", "0");
        properties.setProperty("auto.create.topics.enable", "false");

        Path configFile = temporaryDirectory.resolve("server.properties");
        try (var output = Files.newOutputStream(configFile)) {
            properties.store(output, "Single-node loopback Kafka for the test");
        }
        assertEquals(0, StorageTool.execute(new String[]{"format", "--standalone",
                "-t", Uuid.randomUuid().toString(), "-c", configFile.toString()}, System.out));
        var server = new KafkaRaftServer(KafkaConfig.fromProps(properties), Time.SYSTEM);
        try {
            server.startup();
            sendTwoOrders(bootstrap);
            verifyResumeAndReplay(bootstrap);
        } finally {
            server.shutdown();
            server.awaitShutdown();
        }
    }

    private static void sendTwoOrders(String bootstrap) throws Exception {
        Map<String, Object> settings = Map.of("bootstrap.servers", bootstrap, "request.timeout.ms", 5_000,
                "default.api.timeout.ms", (int) TIMEOUT.toMillis());
        try (var admin = Admin.create(settings)) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1)))
                    .all().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
        try (var producer = new KafkaProducer<>(settings, new StringSerializer(), new StringSerializer())) {
            assertEquals(0L, producer.send(new ProducerRecord<>(TOPIC, 0, "101", "shipped-order-101"))
                    .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).offset());
            assertEquals(1L, producer.send(new ProducerRecord<>(TOPIC, 0, "102", "shipped-order-102"))
                    .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).offset());
        }
    }

    private static void verifyResumeAndReplay(String bootstrap) {
        try (var first = consumer(bootstrap, LIVE_GROUP, "earliest")) {
            first.subscribe(List.of(TOPIC));
            assertOrder(nextRecord(first), 0, "101");
            first.commitSync(Map.of(PARTITION, new OffsetAndMetadata(1)), TIMEOUT);
            System.out.println("live: read offset 0, committed next offset 1");
        }
        try (var resumed = consumer(bootstrap, LIVE_GROUP, "none")) {
            resumed.subscribe(List.of(TOPIC));
            assertOrder(nextRecord(resumed), 1, "102");
            System.out.println("live restarted: resumed at offset 1");
        }
        try (var replay = consumer(bootstrap, REPLAY_GROUP, "none")) {
            replay.assign(List.of(PARTITION));
            replay.seek(PARTITION, 0);
            assertOrder(nextRecord(replay), 0, "101");
            assertOrder(nextRecord(replay), 1, "102");
            System.out.println("rebuild: replayed shipped orders 101 and 102 from offsets 0 and 1");
        }
        try (var live = consumer(bootstrap, LIVE_GROUP, "none")) {
            assertEquals(1L, live.committed(java.util.Set.of(PARTITION), TIMEOUT).get(PARTITION).offset());
            System.out.println("live commit after replay: 1");
        }
    }

    private static KafkaConsumer<String, String> consumer(String bootstrap, String group, String resetPolicy) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, resetPolicy,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1
        ), new StringDeserializer(), new StringDeserializer());
    }

    private static ConsumerRecord<String, String> nextRecord(KafkaConsumer<String, String> consumer) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var records = consumer.poll(Duration.ofMillis(250));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        throw new AssertionError("no record received before the deadline");
    }

    private static void assertOrder(ConsumerRecord<String, String> record, long offset, String orderId) {
        assertEquals(TOPIC, record.topic());
        assertEquals(0, record.partition());
        assertEquals(offset, record.offset());
        assertEquals(orderId, record.key());
        assertEquals("shipped-order-" + orderId, record.value());
    }
}
