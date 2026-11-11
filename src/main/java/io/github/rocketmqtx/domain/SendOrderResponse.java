package io.github.rocketmqtx.domain;

public record SendOrderResponse(String orderId, String messageId, String localTransactionState) {
}
