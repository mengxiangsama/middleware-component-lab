package io.github.rocketmqtx.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rocketmqtx.config.RocketMqProperties;
import io.github.rocketmqtx.domain.CreateOrderRequest;
import io.github.rocketmqtx.domain.OrderCommand;
import io.github.rocketmqtx.domain.SendOrderResponse;
import io.github.rocketmqtx.transaction.OrderTransactionListener;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.common.message.Message;
import org.springframework.stereotype.Service;

@Service
public class OrderMessageService {

    private final TransactionMQProducer producer;
    private final RocketMqProperties properties;
    private final ObjectMapper objectMapper;

    public OrderMessageService(
            TransactionMQProducer producer,
            RocketMqProperties properties,
            ObjectMapper objectMapper) {
        this.producer = producer;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public SendOrderResponse create(CreateOrderRequest request)
            throws JsonProcessingException, MQClientException {
        OrderCommand command = new OrderCommand(
                UUID.randomUUID().toString(), request.productName(), request.amount(), request.outcome());
        Message message = new Message(
                properties.topic(),
                "order-created",
                command.orderId(),
                objectMapper.writeValueAsString(command).getBytes(StandardCharsets.UTF_8));
        message.putUserProperty(OrderTransactionListener.ORDER_ID, command.orderId());

        TransactionSendResult result = producer.sendMessageInTransaction(message, command);
        return new SendOrderResponse(
                command.orderId(), result.getMsgId(), result.getLocalTransactionState().name());
    }
}
