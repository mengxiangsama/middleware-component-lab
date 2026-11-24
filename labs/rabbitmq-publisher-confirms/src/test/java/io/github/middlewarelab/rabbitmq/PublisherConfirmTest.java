package io.github.middlewarelab.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Return;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PublisherConfirmTest {
    @Test
    void aBrokerConfirmCanArriveWithOrWithoutARoutedMessage() throws Exception {
        var factory = new ConnectionFactory();
        factory.setHost("127.0.0.1");
        factory.setPort(Integer.parseInt(requireEnvironment("RABBITMQ_LAB_PORT")));
        factory.setUsername(requireEnvironment("RABBITMQ_LAB_USER"));
        factory.setPassword(requireEnvironment("RABBITMQ_LAB_PASSWORD"));
        factory.setConnectionTimeout(5_000);
        factory.setHandshakeTimeout(5_000);
        factory.setAutomaticRecoveryEnabled(false);

        try (var connection = factory.newConnection("publisher-confirms-lab");
             var publisher = connection.createChannel();
             var reader = connection.createChannel()) {
            String exchange = "publisher-confirms-lab." + UUID.randomUUID();
            publisher.exchangeDeclare(exchange, BuiltinExchangeType.DIRECT, false, true, null);
            String queue = reader.queueDeclare().getQueue();
            reader.queueBind(queue, exchange, "orders.paid");

            var returns = new LinkedBlockingQueue<Return>();
            publisher.addReturnListener(returns::add);
            publisher.confirmSelect();

            byte[] routable = "order-42".getBytes(UTF_8);
            publisher.basicPublish(exchange, "orders.paid", true, properties("paid-42"), routable);
            publisher.waitForConfirmsOrDie(5_000);
            assertNull(returns.poll(), "a matched binding must not return the publish");
            var received = reader.basicGet(queue, true);
            assertNotNull(received, "the matched binding must route the publish to the queue");
            assertEquals("paid-42", received.getProps().getMessageId());
            assertArrayEquals(routable, received.getBody());

            byte[] unroutable = "order-43".getBytes(UTF_8);
            publisher.basicPublish(exchange, "orders.unknown", true, properties("paid-43"), unroutable);
            publisher.waitForConfirmsOrDie(5_000);
            var returned = returns.poll(5, SECONDS);
            assertNotNull(returned, "mandatory publish without a binding must be returned");
            assertEquals(312, returned.getReplyCode());
            assertEquals("NO_ROUTE", returned.getReplyText());
            assertEquals(exchange, returned.getExchange());
            assertEquals("orders.unknown", returned.getRoutingKey());
            assertEquals("paid-43", returned.getProperties().getMessageId());
            assertArrayEquals(unroutable, returned.getBody());
            assertNull(reader.basicGet(queue, true), "the unmatched message must not land in the order queue");

            System.out.println("RabbitMQ " + connection.getServerProperties().get("version"));
            System.out.println("orders.paid: confirm ACK, queue received paid-42");
            System.out.println("orders.unknown: confirm ACK + mandatory return 312 NO_ROUTE for paid-43");
        }
    }

    private static AMQP.BasicProperties properties(String messageId) {
        return new AMQP.BasicProperties.Builder().messageId(messageId).contentType("text/plain").build();
    }

    private static String requireEnvironment(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + key + " or run the lab's verify.sh");
        }
        return value;
    }
}
