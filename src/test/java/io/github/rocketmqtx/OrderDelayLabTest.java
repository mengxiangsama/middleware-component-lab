package io.github.rocketmqtx;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ROCKETMQ_DELAY_TOPIC", matches = "order-delay-[0-9]+")
class OrderDelayLabTest {

    @Test
    void deliversOrderTimeoutCheckAtTheScheduledTime() throws Exception {
        String topic = System.getenv("ROCKETMQ_DELAY_TOPIC");
        String nameserver = "127.0.0.1:9876";
        var producer = new DefaultMQProducer(topic + "-producer");
        producer.setNamesrvAddr(nameserver);
        var consumer = new DefaultMQPushConsumer(topic + "-consumer");
        consumer.setNamesrvAddr(nameserver);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(topic, "*");

        BlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            Instant receivedAt = Instant.now();
            for (var message : messages) {
                deliveries.add(new Delivery(
                        message.getKeys(), new String(message.getBody(), StandardCharsets.UTF_8),
                        Instant.ofEpochMilli(message.getStoreTimestamp()), receivedAt));
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });

        try {
            producer.start();
            consumer.start();

            String orderId = "1001";
            String event = "CHECK_PAYMENT_TIMEOUT";
            Instant deliverAt = Instant.now().plusSeconds(10).truncatedTo(ChronoUnit.SECONDS);
            var message = new Message(topic, event.getBytes(StandardCharsets.UTF_8));
            message.setKeys(orderId);
            message.setDeliverTimeMs(deliverAt.toEpochMilli());
            var result = producer.send(message);
            Instant acceptedAt = Instant.now();
            System.out.printf("send status=%s order=%s acceptedAt=%s deliverAt=%s%n",
                    result.getSendStatus(), orderId, acceptedAt, deliverAt);
            assertThat(result.getSendStatus()).isEqualTo(SendStatus.SEND_OK);
            assertThat(acceptedAt).isBefore(deliverAt);

            Delivery delivery = deliveries.poll(45, TimeUnit.SECONDS);
            assertThat(delivery).as("scheduled order timeout check").isNotNull();
            System.out.printf("received order=%s event=%s storedAt=%s receivedAt=%s%n",
                    delivery.orderId(), delivery.event(), delivery.storedAt(), delivery.receivedAt());
            assertThat(delivery.orderId()).isEqualTo(orderId);
            assertThat(delivery.event()).isEqualTo(event);
            assertThat(delivery.storedAt()).isAfterOrEqualTo(deliverAt);
            assertThat(delivery.receivedAt()).isAfterOrEqualTo(deliverAt);
        } finally {
            consumer.shutdown();
            producer.shutdown();
        }
    }

    private record Delivery(String orderId, String event, Instant storedAt, Instant receivedAt) {}
}
