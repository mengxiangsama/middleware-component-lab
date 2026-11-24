package io.github.middlewarelab.artemis;

import jakarta.jms.JMSContext;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.CoreAddressConfiguration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrderDeadLetterTest {
    private static final String SOURCE = "orders.created";
    private static final String DEAD_LETTERS = "orders.dead";
    private static final String BROKER_URL = "vm://0";
    private static final long RECEIVE_TIMEOUT_MS = 5_000;

    @TempDir
    Path data;

    @Test
    void movesTheOriginalMessageToDeadLettersAfterTwoRollbacks() throws Exception {
        var configuration = new ConfigurationImpl()
                .setPersistenceEnabled(false)
                .setSecurityEnabled(false)
                .setBindingsDirectory(data.resolve("bindings").toString())
                .setJournalDirectory(data.resolve("journal").toString())
                .setPagingDirectory(data.resolve("paging").toString())
                .setLargeMessagesDirectory(data.resolve("large-messages").toString())
                .addAcceptorConfiguration("in-vm", BROKER_URL)
                .addAddressConfiguration(addressWithQueue(SOURCE))
                .addAddressConfiguration(addressWithQueue(DEAD_LETTERS))
                .addAddressSetting(SOURCE, new AddressSettings()
                        .setDeadLetterAddress(SimpleString.of(DEAD_LETTERS))
                        .setMaxDeliveryAttempts(2)
                        .setRedeliveryDelay(0));

        var broker = new EmbeddedActiveMQ().setConfiguration(configuration);
        try {
            broker.start();
            try (var factory = new ActiveMQConnectionFactory(BROKER_URL);
                 var publisher = factory.createContext(JMSContext.AUTO_ACKNOWLEDGE);
                 var worker = factory.createContext(JMSContext.SESSION_TRANSACTED);
                 var auditor = factory.createContext(JMSContext.AUTO_ACKNOWLEDGE);
                 var orders = worker.createConsumer(worker.createQueue(SOURCE));
                 var deadLetters = auditor.createConsumer(auditor.createQueue(DEAD_LETTERS))) {
                var producer = publisher.createProducer();
                var input = publisher.createQueue(SOURCE);
                producer.setProperty("orderId", "order-42").send(input, "unreadable fixture order");

                for (int attempt = 1; attempt <= 2; attempt++) {
                    Message delivery = orders.receive(RECEIVE_TIMEOUT_MS);
                    assertInstanceOf(TextMessage.class, delivery);
                    assertEquals(attempt, delivery.getIntProperty("JMSXDeliveryCount"));
                    assertEquals(attempt == 2, delivery.getJMSRedelivered());
                    assertEquals("order-42", delivery.getStringProperty("orderId"));
                    worker.rollback();
                }

                var deadLetter = assertInstanceOf(TextMessage.class, deadLetters.receive(RECEIVE_TIMEOUT_MS));
                assertEquals("unreadable fixture order", deadLetter.getText());
                assertEquals("order-42", deadLetter.getStringProperty("orderId"));
                assertEquals(SOURCE, deadLetter.getStringProperty("_AMQ_ORIG_ADDRESS"));
                assertEquals(SOURCE, deadLetter.getStringProperty("_AMQ_ORIG_QUEUE"));
                assertNull(orders.receiveNoWait());

                producer.setProperty("orderId", "order-43").send(input, "valid fixture order");
                var next = assertInstanceOf(TextMessage.class, orders.receive(RECEIVE_TIMEOUT_MS));
                assertEquals("order-43", next.getStringProperty("orderId"));
                assertEquals(1, next.getIntProperty("JMSXDeliveryCount"));
                worker.commit();
                assertNull(deadLetters.receiveNoWait());

                System.out.println("order-42: JMSXDeliveryCount 1 -> 2; original message moved to orders.dead");
                System.out.println("order-43: committed on first delivery");
            }
        } finally {
            broker.stop();
        }
    }

    private CoreAddressConfiguration addressWithQueue(String name) {
        return new CoreAddressConfiguration()
                .setName(name)
                .addRoutingType(RoutingType.ANYCAST)
                .addQueueConfiguration(QueueConfiguration.of(name)
                        .setAddress(name)
                        .setRoutingType(RoutingType.ANYCAST)
                        .setDurable(false));
    }
}
