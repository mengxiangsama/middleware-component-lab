package io.github.rocketmqtx;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ROCKETMQ_FIFO_TOPIC", matches = "order-fifo-[0-9]+")
class OrderFifoLabTest {

    @Test
    void consumesEachOrderInTheSequenceItWasSent() throws Exception {
        String topic = System.getenv("ROCKETMQ_FIFO_TOPIC");
        String nameserver = "127.0.0.1:9876";
        var producer = new DefaultMQProducer(topic + "-producer");
        producer.setNamesrvAddr(nameserver);
        var consumer = new DefaultMQPushConsumer(topic + "-consumer");
        consumer.setNamesrvAddr(nameserver);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(topic, "*");

        List<Integer> orders = List.of(1001, 1002);
        List<String> events = List.of("CREATED", "PAID", "SHIPPED");
        BlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();
        consumer.registerMessageListener((MessageListenerOrderly) (messages, context) -> {
            for (var message : messages) {
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                System.out.printf("received %s queue=%d offset=%d%n",
                        body, message.getQueueId(), message.getQueueOffset());
                deliveries.add(new Delivery(body, message.getQueueId(), message.getQueueOffset()));
            }
            return ConsumeOrderlyStatus.SUCCESS;
        });

        try {
            producer.start();
            consumer.start();
            for (String event : events) {
                for (int orderId : orders) {
                    byte[] body = (orderId + " " + event).getBytes(StandardCharsets.UTF_8);
                    var result = producer.send(new Message(topic, body), (queues, message, key) -> {
                        var sortedQueues = queues.stream().sorted().toList();
                        return sortedQueues.get(Math.floorMod((int) key, sortedQueues.size()));
                    }, orderId);
                    assertThat(result.getSendStatus()).isEqualTo(SendStatus.SEND_OK);
                }
            }

            var received = new ArrayList<Delivery>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            for (int i = 0; i < orders.size() * events.size(); i++) {
                Delivery delivery = deliveries.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                assertThat(delivery).as("delivery %s", i + 1).isNotNull();
                received.add(delivery);
            }

            for (int orderId : orders) {
                var orderDeliveries = received.stream()
                        .filter(delivery -> delivery.body().startsWith(orderId + " "))
                        .toList();
                assertThat(orderDeliveries).extracting(Delivery::body)
                        .containsExactlyElementsOf(events.stream().map(event -> orderId + " " + event).toList());
                assertThat(orderDeliveries).extracting(Delivery::queueId)
                        .containsOnly(Math.floorMod(orderId, 2));
                assertThat(orderDeliveries).extracting(Delivery::offset)
                        .containsExactly(0L, 1L, 2L);
            }
        } finally {
            consumer.shutdown();
            producer.shutdown();
        }
    }

    private record Delivery(String body, int queueId, long offset) {}
}
