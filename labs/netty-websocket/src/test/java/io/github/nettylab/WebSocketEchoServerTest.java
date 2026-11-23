package io.github.nettylab;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebSocketEchoServerTest {
    @Test
    void echoesFragmentedTextAndCompletesNormalClose() throws Exception {
        EventLoopGroup eventLoops = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        try {
            var server = WebSocketEchoServer.bind(eventLoops, 0);
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            var listener = new EchoListener();
            WebSocket client = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), listener)
                    .get(5, TimeUnit.SECONDS);
            try {
                client.sendText("Netty ", false).get(5, TimeUnit.SECONDS);
                client.sendText("你好", true).get(5, TimeUnit.SECONDS);
                assertEquals("Netty 你好", listener.echo.get(5, TimeUnit.SECONDS));

                client.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
                assertEquals(WebSocket.NORMAL_CLOSURE, listener.closeCode.get(5, TimeUnit.SECONDS));
            } finally {
                client.abort();
            }
        } finally {
            eventLoops.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private static final class EchoListener implements WebSocket.Listener {
        private final CompletableFuture<String> echo = new CompletableFuture<>();
        private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        private final StringBuilder text = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                echo.complete(text.toString());
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            echo.completeExceptionally(error);
            closeCode.completeExceptionally(error);
        }
    }
}
