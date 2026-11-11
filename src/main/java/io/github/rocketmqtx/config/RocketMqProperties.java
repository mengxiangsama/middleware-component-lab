package io.github.rocketmqtx.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rocketmq")
public record RocketMqProperties(
        boolean enabled,
        String nameServer,
        String topic,
        String producerGroup,
        String consumerGroup) {
}
