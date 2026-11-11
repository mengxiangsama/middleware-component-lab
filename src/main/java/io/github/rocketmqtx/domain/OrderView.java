package io.github.rocketmqtx.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderView(
        String orderId,
        String productName,
        BigDecimal amount,
        String status,
        Instant createdAt) {
}
