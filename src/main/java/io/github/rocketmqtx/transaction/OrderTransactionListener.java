package io.github.rocketmqtx.transaction;

import io.github.rocketmqtx.domain.OrderCommand;
import io.github.rocketmqtx.domain.TransactionOutcome;
import io.github.rocketmqtx.service.LocalOrderService;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OrderTransactionListener implements TransactionListener {

    public static final String ORDER_ID = "orderId";
    private static final Logger log = LoggerFactory.getLogger(OrderTransactionListener.class);

    private final LocalOrderService localOrderService;

    public OrderTransactionListener(LocalOrderService localOrderService) {
        this.localOrderService = localOrderService;
    }

    @Override
    public LocalTransactionState executeLocalTransaction(Message message, Object argument) {
        OrderCommand command = (OrderCommand) argument;
        try {
            if (command.outcome() == TransactionOutcome.ROLLBACK) {
                log.info("Simulating local transaction rollback for order {}", command.orderId());
                return LocalTransactionState.ROLLBACK_MESSAGE;
            }

            localOrderService.createCommittedOrder(command);
            if (command.outcome() == TransactionOutcome.UNKNOWN) {
                log.info("Local transaction committed but returning UNKNOWN for order {}", command.orderId());
                return LocalTransactionState.UNKNOW;
            }
            return LocalTransactionState.COMMIT_MESSAGE;
        } catch (RuntimeException exception) {
            log.error("Local transaction failed for order {}", command.orderId(), exception);
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt message) {
        String orderId = message.getUserProperty(ORDER_ID);
        boolean committed = orderId != null && localOrderService.isCommitted(orderId);
        log.info("Transaction check for order {} resolved to {}", orderId, committed ? "COMMIT" : "ROLLBACK");
        return committed ? LocalTransactionState.COMMIT_MESSAGE : LocalTransactionState.ROLLBACK_MESSAGE;
    }
}
