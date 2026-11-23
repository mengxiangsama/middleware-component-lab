package io.github.nettylab;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadIdleTimeoutTest {
    @Test
    void inboundReadPostponesCloseUntilNextFullIdlePeriod() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            channel.freezeTime();
            channel.pipeline().addLast(new IdleStateHandler(10, 0, 0), new CloseOnReadIdle());

            channel.advanceTimeBy(6, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            channel.writeInbound("client activity");
            assertEquals("client activity", channel.readInbound());

            channel.advanceTimeBy(4, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            assertTrue(channel.isActive(), "the read at t=6 resets the original t=10 deadline");

            channel.advanceTimeBy(6, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            assertFalse(channel.isOpen(), "ten seconds without a read closes the channel");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static final class CloseOnReadIdle extends ChannelInboundHandlerAdapter {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
            if (event instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
                ctx.close();
                return;
            }
            ctx.fireUserEventTriggered(event);
        }
    }
}
