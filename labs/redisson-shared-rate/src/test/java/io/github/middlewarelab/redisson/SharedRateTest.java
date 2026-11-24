package io.github.middlewarelab.redisson;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.redisson.Redisson;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SharedRateTest {
    private static final String HOST = "127.0.0.1";
    private static final String LIMITER = "quote-api:global";
    private static final Duration WINDOW = Duration.ofHours(1);

    @TempDir
    Path workspace;

    @Test
    @Timeout(60)
    void twoJvmProcessesShareTwoPermits() throws Exception {
        int port;
        try (var listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress(HOST, 0));
            port = listener.getLocalPort();
        }

        var binary = System.getenv().getOrDefault("VALKEY_SERVER", "valkey-server");
        var serverLog = workspace.resolve("valkey.log");
        var server = new ProcessBuilder(
                binary, "--bind", HOST, "--port", Integer.toString(port),
                "--save", "", "--appendonly", "no", "--dir", workspace.toString())
                .redirectErrorStream(true)
                .redirectOutput(serverLog.toFile())
                .start();
        try {
            waitForValkey(server, port, serverLog);
            String address = "redis://" + HOST + ":" + port;
            var client = connect(address);
            try {
                var limiter = client.getRateLimiter(LIMITER);
                assertTrue(limiter.trySetRate(RateType.OVERALL, 2, WINDOW));
                assertTrue(limiter.tryAcquire(), "A should take the first permit");
                assertEquals(1, limiter.availablePermits());

                var output = workspace.resolve("client-b.log");
                var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
                var secondClient = new ProcessBuilder(java, "-XX:ActiveProcessorCount=2",
                        "-cp", classpath, ClientB.class.getName(), address)
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
                try {
                    assertTrue(secondClient.waitFor(20, SECONDS), "second JVM did not finish");
                    String childOutput = Files.readString(output);
                    assertEquals(0, secondClient.exitValue(), childOutput);
                    assertTrue(childOutput.contains("B acquired one permit from the same limiter"), childOutput);
                } finally {
                    stop(secondClient);
                }

                assertFalse(limiter.tryAcquire(), "third attempt must fail across both clients");
                assertEquals(0, limiter.availablePermits());
                System.out.println("A acquired, B acquired, A denied; 0 shared permits remain");
            } finally {
                client.shutdown();
            }
        } finally {
            stop(server);
        }
    }

    public static class ClientB {
        public static void main(String[] args) {
            var client = connect(args[0]);
            try {
                var limiter = client.getRateLimiter(LIMITER);
                if (limiter.trySetRate(RateType.OVERALL, 99, WINDOW)) {
                    throw new IllegalStateException("B unexpectedly changed the existing rate");
                }
                if (!limiter.tryAcquire()) {
                    throw new IllegalStateException("B could not take the second permit");
                }
                System.out.println("B acquired one permit from the same limiter");
            } finally {
                client.shutdown();
            }
        }
    }

    private static RedissonClient connect(String address) {
        var config = new Config();
        config.setThreads(2).setNettyThreads(2);
        config.useSingleServer()
                .setAddress(address)
                .setConnectTimeout(2000)
                .setTimeout(2000)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(2)
                .setSubscriptionConnectionMinimumIdleSize(1)
                .setSubscriptionConnectionPoolSize(2);
        return Redisson.create(config);
    }

    private void waitForValkey(Process server, int port, Path log) throws IOException, InterruptedException {
        var deadline = System.nanoTime() + SECONDS.toNanos(12);
        while (server.isAlive() && System.nanoTime() < deadline) {
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress(HOST, port), 400);
                socket.setSoTimeout(400);
                socket.getOutputStream().write("PING\r\n".getBytes(US_ASCII));
                var reply = new BufferedReader(new InputStreamReader(socket.getInputStream(), US_ASCII));
                if ("+PONG".equals(reply.readLine())) {
                    return;
                }
            } catch (IOException notReady) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("Valkey did not answer PING\n" + Files.readString(log));
    }

    private static void stop(Process process) throws InterruptedException {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(3, SECONDS)) {
                process.destroyForcibly().waitFor(3, SECONDS);
            }
        }
    }
}
