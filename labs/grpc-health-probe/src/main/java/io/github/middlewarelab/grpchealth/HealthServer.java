package io.github.middlewarelab.grpchealth;

import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class HealthServer {
    private static final String READINESS = "readiness";
    private static final String LIVENESS = "liveness";

    public static void main(String[] args) throws Exception {
        int port = parsePort(args);
        var health = new HealthStatusManager();
        setReadiness(health, ServingStatus.NOT_SERVING);
        health.setStatus(LIVENESS, ServingStatus.SERVING);
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", port))
                .addService(health.getHealthService())
                .build()
                .start();

        try (var commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            System.out.println("PORT=" + server.getPort());
            System.out.flush();
            for (String command; (command = commands.readLine()) != null; ) {
                switch (command) {
                    case "ready" -> acknowledge(health, ServingStatus.SERVING);
                    case "unready" -> acknowledge(health, ServingStatus.NOT_SERVING);
                    case "stop" -> {
                        return;
                    }
                    default -> System.err.println("Expected ready, unready or stop");
                }
            }
        } finally {
            health.enterTerminalState();
            if (!server.shutdown().awaitTermination(5, TimeUnit.SECONDS)) {
                server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void setReadiness(HealthStatusManager health, ServingStatus status) {
        health.setStatus(READINESS, status);
        health.setStatus(HealthStatusManager.SERVICE_NAME_ALL_SERVICES, status);
    }

    private static void acknowledge(HealthStatusManager health, ServingStatus status) {
        setReadiness(health, status);
        System.out.println("STATE=" + status.name());
        System.out.flush();
    }

    private static int parsePort(String[] args) {
        if (args.length == 0) {
            return 0;
        }
        if (args.length == 1) {
            int port = Integer.parseInt(args[0]);
            if (port >= 0 && port <= 65535) {
                return port;
            }
        }
        throw new IllegalArgumentException("Usage: java -jar grpc-health-probe-lab.jar [port]");
    }
}
