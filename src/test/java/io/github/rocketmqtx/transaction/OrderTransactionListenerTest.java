package io.github.rocketmqtx.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.rocketmqtx.domain.OrderCommand;
import io.github.rocketmqtx.domain.TransactionOutcome;
import io.github.rocketmqtx.service.LocalOrderService;
import java.math.BigDecimal;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;

@MockitoSettings
class OrderTransactionListenerTest {

    @Mock
    private LocalOrderService localOrderService;
    private OrderTransactionListener listener;

    @BeforeEach
    void setUp() {
        listener = new OrderTransactionListener(localOrderService);
    }

    @Test
    void commitsAfterSuccessfulLocalTransaction() {
        OrderCommand command = command(TransactionOutcome.COMMIT);

        LocalTransactionState state = listener.executeLocalTransaction(new Message(), command);

        assertThat(state).isEqualTo(LocalTransactionState.COMMIT_MESSAGE);
        verify(localOrderService).createCommittedOrder(command);
    }

    @Test
    void returnsUnknownAfterCommittingForCheckbackDemo() {
        OrderCommand command = command(TransactionOutcome.UNKNOWN);

        LocalTransactionState state = listener.executeLocalTransaction(new Message(), command);

        assertThat(state).isEqualTo(LocalTransactionState.UNKNOW);
        verify(localOrderService).createCommittedOrder(command);
    }

    @Test
    void rollsBackWithoutCreatingAnOrder() {
        LocalTransactionState state = listener.executeLocalTransaction(
                new Message(), command(TransactionOutcome.ROLLBACK));

        assertThat(state).isEqualTo(LocalTransactionState.ROLLBACK_MESSAGE);
    }

    @Test
    void rollsBackWhenTheLocalTransactionThrows() {
        OrderCommand command = command(TransactionOutcome.COMMIT);
        doThrow(new IllegalStateException("db unavailable"))
                .when(localOrderService).createCommittedOrder(command);

        LocalTransactionState state = listener.executeLocalTransaction(new Message(), command);

        assertThat(state).isEqualTo(LocalTransactionState.ROLLBACK_MESSAGE);
    }

    @Test
    void checkbackUsesThePersistedTransactionState() {
        MessageExt message = new MessageExt();
        message.putUserProperty(OrderTransactionListener.ORDER_ID, "order-1");
        when(localOrderService.isCommitted("order-1")).thenReturn(true);

        assertThat(listener.checkLocalTransaction(message))
                .isEqualTo(LocalTransactionState.COMMIT_MESSAGE);
    }

    private OrderCommand command(TransactionOutcome outcome) {
        return new OrderCommand("order-1", "Keyboard", new BigDecimal("99.00"), outcome);
    }
}
