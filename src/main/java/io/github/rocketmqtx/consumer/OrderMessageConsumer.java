package io.github.rocketmqtx.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rocketmqtx.domain.OrderCommand;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderMessageConsumer.class);
    private final JdbcTemplate jdbcTemplate;

    public OrderMessageConsumer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public MessageListenerConcurrently listener(ObjectMapper objectMapper) {
        return (messages, context) -> {
            for (MessageExt message : messages) {
                if (!consume(message, context, objectMapper)) {
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        };
    }

    private boolean consume(
            MessageExt message,
            ConsumeConcurrentlyContext context,
            ObjectMapper objectMapper) {
        try {
            OrderCommand command = objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8), OrderCommand.class);
            jdbcTemplate.update(
                    "insert into consumed_event(event_id, order_id, consumed_at) values (?, ?, ?)",
                    message.getMsgId(), command.orderId(), Timestamp.from(Instant.now()));
            log.info("Consumed order event: orderId={}, queue={}", command.orderId(), context.getMessageQueue());
            return true;
        } catch (DuplicateKeyException duplicate) {
            log.info("Ignoring duplicate event {}", message.getMsgId());
            return true;
        } catch (Exception exception) {
            log.error("Order event consumption failed for message {}", message.getMsgId(), exception);
            return false;
        }
    }
}
