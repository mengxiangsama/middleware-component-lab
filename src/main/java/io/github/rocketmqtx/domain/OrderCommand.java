package io.github.rocketmqtx.domain;

import java.math.BigDecimal;

public record OrderCommand(
        String orderId,
        String productName,
        BigDecimal amount,
        TransactionOutcome outcome) {
}
