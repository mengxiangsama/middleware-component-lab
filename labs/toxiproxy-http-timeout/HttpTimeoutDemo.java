import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class HttpTimeoutDemo {
    private static final String LOOPBACK = "127.0.0.1";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(1);
    private static final int LATENCY_MILLIS = 3_500;
    private static final Pattern PROXY_ADDRESS =
            Pattern.compile("\"listen\"\\s*:\\s*\"(127\\.0\\.0\\.1:[0-9]+)\"");

    public static void main(String[] args) throws Exception {
        String binary = args.length == 0 ? "toxiproxy-server" : args[0];
        String proxyName = "stock-demo-" + UUID.randomUUID();
        var runtimeDirectory = Files.createTempDirectory("toxiproxy-stock-demo-");
        var config = runtimeDirectory.resolve("proxies.json");
        var log = runtimeDirectory.resolve("toxiproxy.log");
        var backend = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        var received = new AtomicInteger();

        backend.createContext("/stock", exchange -> {
            try (exchange) {
                received.incrementAndGet();
                byte[] body = "stock=7".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Connection", "close");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        backend.start();
        Process toxiproxy = null;
        try {
            Files.writeString(config, """
                    [{"name":"%s","listen":"127.0.0.1:0","upstream":"127.0.0.1:%d","enabled":true}]
                    """.formatted(proxyName, backend.getAddress().getPort()));
            int apiPort = unusedPort();
            toxiproxy = new ProcessBuilder(binary, "-host", LOOPBACK, "-port",
                    Integer.toString(apiPort), "-config", config.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            URI proxyApi = URI.create("http://" + LOOPBACK + ":" + apiPort + "/proxies/" + proxyName);
            var control = client();
            URI stockUri = waitForProxy(toxiproxy, control, proxyApi);
            var data = client();

            assertStock(data, stockUri, received, 1, "normal");
            URI toxics = URI.create(proxyApi + "/toxics");
            var add = HttpRequest.newBuilder(toxics).timeout(CONNECT_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"name":"slow-response","type":"latency","stream":"downstream",
                             "attributes":{"latency":%d,"jitter":0}}
                            """.formatted(LATENCY_MILLIS)))
                    .build();
            assertStatus(control, add, 200);

            assertRequestTimeout(data, stockUri, received);

            var remove = HttpRequest.newBuilder(URI.create(toxics + "/slow-response"))
                    .timeout(CONNECT_TIMEOUT).DELETE().build();
            assertStatus(control, remove, 204);
            assertStock(data, stockUri, received, 3, "restored");
        } catch (Exception | AssertionError failure) {
            try {
                if (Files.exists(log)) {
                    System.err.print(Files.readString(log));
                }
            } catch (IOException logFailure) {
                failure.addSuppressed(logFailure);
            }
            throw failure;
        } finally {
            try {
                if (toxiproxy != null) {
                    toxiproxy.destroy();
                    if (!toxiproxy.waitFor(5, TimeUnit.SECONDS)) {
                        toxiproxy.destroyForcibly().waitFor();
                    }
                }
            } finally {
                backend.stop(0);
                Files.deleteIfExists(config);
                Files.deleteIfExists(log);
                Files.delete(runtimeDirectory);
            }
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .proxy(HttpClient.Builder.NO_PROXY).build();
    }

    private static int unusedPort() throws IOException {
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
            return socket.getLocalPort();
        }
    }

    private static URI waitForProxy(Process process, HttpClient client, URI api)
            throws InterruptedException {
        var request = HttpRequest.newBuilder(api).timeout(Duration.ofMillis(300)).GET().build();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (process.isAlive() && System.nanoTime() < deadline) {
            try {
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                var address = PROXY_ADDRESS.matcher(response.body());
                if (response.statusCode() == 200 && address.find()) {
                    return URI.create("http://" + address.group(1) + "/stock");
                }
            } catch (IOException ignored) {
                // Readiness checks may reach the port before the child starts accepting requests.
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Toxiproxy did not expose the configured loopback listener");
    }

    private static void assertStock(HttpClient client, URI uri, AtomicInteger received, int count, String phase)
            throws IOException, InterruptedException {
        var response = client.send(stockRequest(uri), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || !response.body().equals("stock=7") || received.get() != count) {
            throw new AssertionError("Unexpected stock result or number of upstream requests: " + phase);
        }
        System.out.printf("%s: HTTP %d %s, upstream requests=%d%n",
                phase, response.statusCode(), response.body(), count);
    }

    private static void assertRequestTimeout(HttpClient client, URI uri, AtomicInteger received)
            throws IOException, InterruptedException {
        long started = System.nanoTime();
        try {
            client.send(stockRequest(uri), HttpResponse.BodyHandlers.ofString());
            throw new AssertionError("Expected a request timeout with downstream latency");
        } catch (HttpConnectTimeoutException e) {
            throw new AssertionError("Could not establish the proxy connection", e);
        } catch (HttpTimeoutException e) {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (received.get() != 2) {
                throw new AssertionError("The timed-out request did not reach the backend exactly once", e);
            }
            System.out.printf("delayed: %s after %d ms, upstream requests=%d%n",
                    e.getClass().getSimpleName(), elapsed, received.get());
        }
    }

    private static HttpRequest stockRequest(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build();
    }

    private static void assertStatus(HttpClient client, HttpRequest request, int expected)
            throws IOException, InterruptedException {
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != expected) {
            throw new AssertionError("Toxiproxy returned HTTP " + response.statusCode() + ": " + response.body());
        }
    }
}
