package io.github.rocketmqtx;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ROCKETMQ_TAG_TOPIC", matches = "order-tag-[0-9]+")
class OrderTagFilterLabTest {

    private static final String NAMESERVER = "127.0.0.1:9876";

    @Test
    void deliversOnlySubscribedOrderTags() throws Exception {
        String topic = System.getenv("ROCKETMQ_TAG_TOPIC");
        BlockingQueue<String> allTags = new LinkedBlockingQueue<>();
        BlockingQueue<String> selectedTags = new LinkedBlockingQueue<>();
        var producer = new DefaultMQProducer(topic + "-producer");
        producer.setNamesrvAddr(NAMESERVER);
        var control = consumer(topic, "-all", "*", allTags);
        var filtered = consumer(topic, "-selected", "PAID || REFUNDED", selectedTags);

        try {
            producer.start();
            control.start();
            filtered.start();

            for (String tag : List.of("PAID", "SHIPPED", "REFUNDED")) {
                var message = new Message(topic, tag, ("order-1001:" + tag).getBytes(StandardCharsets.UTF_8));
                var result = producer.send(message);
                System.out.printf("sent tag=%s status=%s queueOffset=%d%n",
                        tag, result.getSendStatus(), result.getQueueOffset());
                assertThat(result.getSendStatus()).isEqualTo(SendStatus.SEND_OK);
            }

            assertThat(List.of(awaitTag(allTags), awaitTag(allTags), awaitTag(allTags)))
                    .containsExactlyInAnyOrder("PAID", "SHIPPED", "REFUNDED");
            assertThat(List.of(awaitTag(selectedTags), awaitTag(selectedTags)))
                    .containsExactlyInAnyOrder("PAID", "REFUNDED");
            assertThat(selectedTags.poll(2, TimeUnit.SECONDS)).as("unexpected filtered delivery").isNull();
        } finally {
            filtered.shutdown();
            control.shutdown();
            producer.shutdown();
        }
    }

    private static DefaultMQPushConsumer consumer(
            String topic, String groupSuffix, String expression, BlockingQueue<String> tags) throws MQClientException {
        String group = topic + groupSuffix;
        var consumer = new DefaultMQPushConsumer(group);
        consumer.setNamesrvAddr(NAMESERVER);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(topic, expression);
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (var message : messages) {
                System.out.printf("received group=%s tag=%s queueOffset=%d%n",
                        group, message.getTags(), message.getQueueOffset());
                tags.add(message.getTags());
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        return consumer;
    }

    private static String awaitTag(BlockingQueue<String> tags) throws InterruptedException {
        String tag = tags.poll(30, TimeUnit.SECONDS);
        assertThat(tag).as("expected tag delivery").isNotNull();
        return tag;
    }
}
