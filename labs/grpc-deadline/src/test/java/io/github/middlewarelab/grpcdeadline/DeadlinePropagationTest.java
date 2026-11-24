package io.github.middlewarelab.grpcdeadline;

import io.grpc.BindableService;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadlinePropagationTest {
    private static final String LOOPBACK = "127.0.0.1";
    private static final int SHORT_BUDGET_SECONDS = 2;
    private final List<Server> servers = new ArrayList<>();
    private final List<ManagedChannel> channels = new ArrayList<>();

    @Test
    void forwardsTheCurrentDeadlineAndCancelsTheDownstreamCall() throws Exception {
        var inventory = new InventoryFixture();
        var inventoryStub = InventoryServiceGrpc.newBlockingStub(connect(start(inventory)));
        var order = OrderServiceGrpc.newBlockingStub(connect(start(new OrderFacade(inventoryStub))));

        var available = order.withDeadlineAfter(10, SECONDS)
                .checkAvailability(StockRequest.newBuilder().setSku("sku-42").build());
        assertEquals("sku-42", available.getSku());
        assertEquals(7, available.getQuantity());

        var timedOut = assertThrows(StatusRuntimeException.class, () ->
                order.withDeadlineAfter(SHORT_BUDGET_SECONDS, SECONDS)
                        .checkAvailability(StockRequest.newBuilder().setSku("held").build()));
        assertEquals(Status.Code.DEADLINE_EXCEEDED, timedOut.getStatus().getCode());

        Long remainingNanos = inventory.heldBudgetNanos.get(5, SECONDS);
        assertNotNull(remainingNanos, "inventory must receive a deadline");
        assertTrue(remainingNanos > 0 && remainingNanos < SECONDS.toNanos(3),
                "inventory should see the short request budget");
        inventory.heldCancellation.get(5, SECONDS);
        System.out.println("sku-42: available quantity=7");
        System.out.println("held: inventory received a deadline and cancellation; client status=DEADLINE_EXCEEDED");
    }

    private Server start(BindableService service) throws IOException {
        var server = NettyServerBuilder.forAddress(new InetSocketAddress(LOOPBACK, 0))
                .addService(service).build().start();
        servers.add(server);
        return server;
    }

    private ManagedChannel connect(Server server) {
        var channel = NettyChannelBuilder.forAddress(LOOPBACK, server.getPort()).usePlaintext().build();
        channels.add(channel);
        return channel;
    }

    @AfterEach
    void closeServices() throws InterruptedException {
        channels.forEach(ManagedChannel::shutdownNow);
        servers.forEach(Server::shutdownNow);
        for (var channel : channels) {
            assertTrue(channel.awaitTermination(5, SECONDS), "channel did not terminate");
        }
        for (var server : servers) {
            assertTrue(server.awaitTermination(5, SECONDS), "server did not terminate");
        }
    }

    private static final class OrderFacade extends OrderServiceGrpc.OrderServiceImplBase {
        private final InventoryServiceGrpc.InventoryServiceBlockingStub inventory;

        private OrderFacade(InventoryServiceGrpc.InventoryServiceBlockingStub inventory) {
            this.inventory = inventory;
        }

        @Override
        public void checkAvailability(StockRequest request, StreamObserver<AvailabilityReply> reply) {
            try {
                var stock = inventory.readStock(request);
                reply.onNext(AvailabilityReply.newBuilder()
                        .setSku(request.getSku()).setQuantity(stock.getQuantity()).build());
                reply.onCompleted();
            } catch (StatusRuntimeException failure) {
                reply.onError(failure);
            }
        }
    }

    private static final class InventoryFixture extends InventoryServiceGrpc.InventoryServiceImplBase {
        private final CompletableFuture<Long> heldBudgetNanos = new CompletableFuture<>();
        private final CompletableFuture<Void> heldCancellation = new CompletableFuture<>();

        @Override
        public void readStock(StockRequest request, StreamObserver<StockReply> reply) {
            if ("held".equals(request.getSku())) {
                // Keep this fixture RPC open until gRPC cancels the current request.
                var context = Context.current();
                var deadline = context.getDeadline();
                heldBudgetNanos.complete(deadline == null ? null : deadline.timeRemaining(NANOSECONDS));
                context.addListener(ignored -> heldCancellation.complete(null), Runnable::run);
                return;
            }

            reply.onNext(StockReply.newBuilder().setQuantity(7).build());
            reply.onCompleted();
        }
    }
}
