package io.github.rocketmqtx.api;

import io.github.rocketmqtx.domain.CreateOrderRequest;
import io.github.rocketmqtx.domain.OrderView;
import io.github.rocketmqtx.domain.SendOrderResponse;
import io.github.rocketmqtx.service.LocalOrderService;
import io.github.rocketmqtx.service.OrderMessageService;
import jakarta.validation.Valid;
import java.util.Map;
import org.apache.rocketmq.client.exception.MQClientException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderMessageService orderMessageService;
    private final LocalOrderService localOrderService;

    public OrderController(OrderMessageService orderMessageService, LocalOrderService localOrderService) {
        this.orderMessageService = orderMessageService;
        this.localOrderService = localOrderService;
    }

    @PostMapping
    public ResponseEntity<SendOrderResponse> create(@Valid @RequestBody CreateOrderRequest request) throws Exception {
        return ResponseEntity.accepted().body(orderMessageService.create(request));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderView> get(@PathVariable String orderId) {
        return localOrderService.findOrder(orderId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(MQClientException.class)
    public ResponseEntity<Map<String, String>> handle(Exception exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error", exception.getClass().getSimpleName(),
                        "message", String.valueOf(exception.getMessage())));
    }
}
