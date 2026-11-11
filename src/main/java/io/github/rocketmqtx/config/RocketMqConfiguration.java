package io.github.rocketmqtx.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rocketmqtx.consumer.OrderMessageConsumer;
import io.github.rocketmqtx.transaction.OrderTransactionListener;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RocketMqProperties.class)
@ConditionalOnProperty(prefix = "app.rocketmq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RocketMqConfiguration {

    @Bean(destroyMethod = "shutdown")
    TransactionMQProducer transactionProducer(
            RocketMqProperties properties,
            OrderTransactionListener transactionListener) throws Exception {
        ExecutorService checkExecutor = new ThreadPoolExecutor(
                2, 5, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2_000),
                runnable -> new Thread(runnable, "transaction-checker"));

        TransactionMQProducer producer = new TransactionMQProducer(properties.producerGroup());
        producer.setNamesrvAddr(properties.nameServer());
        producer.setExecutorService(checkExecutor);
        producer.setTransactionListener(transactionListener);
        producer.start();
        return producer;
    }

    @Bean(destroyMethod = "shutdown")
    DefaultMQPushConsumer orderConsumer(
            RocketMqProperties properties,
            ObjectMapper objectMapper,
            OrderMessageConsumer orderMessageConsumer) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(properties.consumerGroup());
        consumer.setNamesrvAddr(properties.nameServer());
        consumer.subscribe(properties.topic(), "*");
        MessageListenerConcurrently listener = orderMessageConsumer.listener(objectMapper);
        consumer.registerMessageListener(listener);
        consumer.start();
        return consumer;
    }
}
