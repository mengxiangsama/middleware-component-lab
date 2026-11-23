package io.github.nettylab;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public final class WebSocketEchoServer {
    private static final String HOST = "127.0.0.1";
    private static final String PATH = "/ws";
    private static final int DEFAULT_PORT = 8091;
    private static final int MAX_MESSAGE_BYTES = 64 * 1024;

    public static void main(String[] args) throws InterruptedException {
        int port = args.length == 0 ? DEFAULT_PORT : Integer.parseInt(args[0]);
        EventLoopGroup eventLoops = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        Runnable stop = () -> eventLoops.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        Runtime.getRuntime().addShutdownHook(new Thread(stop, "netty-shutdown"));

        try {
            Channel server = bind(eventLoops, port);
            int boundPort = ((InetSocketAddress) server.localAddress()).getPort();
            System.out.printf("Listening on ws://%s:%d%s%n", HOST, boundPort, PATH);
            server.closeFuture().sync();
        } finally {
            stop.run();
        }
    }

    static Channel bind(EventLoopGroup eventLoops, int port) throws InterruptedException {
        return new ServerBootstrap()
                .group(eventLoops)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(
                                new HttpServerCodec(),
                                new HttpObjectAggregator(MAX_MESSAGE_BYTES),
                                new WebSocketServerProtocolHandler(PATH, null, false, MAX_MESSAGE_BYTES),
                                new WebSocketFrameAggregator(MAX_MESSAGE_BYTES),
                                new EchoHandler());
                    }
                })
                .bind(HOST, port)
                .sync()
                .channel();
    }

    private static final class EchoHandler extends SimpleChannelInboundHandler<Object> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object message) {
            if (message instanceof TextWebSocketFrame text) {
                ctx.writeAndFlush(new TextWebSocketFrame(text.text()));
            } else if (message instanceof BinaryWebSocketFrame) {
                closeWithStatus(ctx, WebSocketCloseStatus.INVALID_MESSAGE_TYPE);
            } else if (message instanceof FullHttpRequest) {
                var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
                HttpUtil.setContentLength(response, 0);
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (cause instanceof TooLongFrameException) {
                closeWithStatus(ctx, WebSocketCloseStatus.MESSAGE_TOO_BIG);
                return;
            }
            ctx.close();
        }

        private static void closeWithStatus(ChannelHandlerContext ctx, WebSocketCloseStatus status) {
            ctx.writeAndFlush(new CloseWebSocketFrame(status)).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
