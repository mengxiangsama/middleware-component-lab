package io.github.middlewarelab.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderRoutingTest {
    private static final String RELEASE_TRACK = "X-Release-Track";

    @Test
    void routesByHeaderAndDoesNotFailOverOnConnectionFailure() throws Exception {
        try (var stable = new Catalog("stable"); var canary = new Catalog("canary");
             var gateway = (ReactiveWebServerApplicationContext) SpringApplication.run(
                     GatewayConfig.class,
                     "--server.address=127.0.0.1",
                     "--server.port=0",
                     "--catalog.stable-uri=" + stable.address(),
                     "--catalog.canary-uri=" + canary.address())) {
            String gatewayUri = "http://127.0.0.1:" + gateway.getWebServer().getPort();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

            assertCatalog(call(client, gatewayUri, "/catalog/items", "canary"), "canary:canary");
            assertCatalog(call(client, gatewayUri, "/catalog/items", null), "stable:unmarked");
            assertCatalog(call(client, gatewayUri, "/catalog/items", "canary-preview"), "stable:canary-preview");
            assertThat(call(client, gatewayUri, "/different/items", "canary").statusCode()).isEqualTo(404);
            assertThat(canary.requests.get()).isEqualTo(1);
            assertThat(stable.requests.get()).isEqualTo(2);

            canary.close();
            assertThat(call(client, gatewayUri, "/catalog/items", "canary").statusCode()).isEqualTo(500);
            assertThat(stable.requests.get()).isEqualTo(2);

            assertCatalog(call(client, gatewayUri, "/catalog/items", null), "stable:unmarked");
            assertThat(stable.requests.get()).isEqualTo(3);
        }
    }

    private static void assertCatalog(HttpResponse<String> response, String body) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(body);
    }

    private static HttpResponse<String> call(HttpClient client, String gatewayUri, String path, String releaseTrack)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(gatewayUri + path)).timeout(Duration.ofSeconds(10));
        if (releaseTrack != null) {
            request.header(RELEASE_TRACK, releaseTrack);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class GatewayConfig {
    }

    private static final class Catalog implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();

        Catalog(String version) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/catalog/items", exchange -> {
                try (exchange) {
                    requests.incrementAndGet();
                    String requestTrack = Objects.requireNonNullElse(
                            exchange.getRequestHeaders().getFirst(RELEASE_TRACK), "unmarked");
                    byte[] response = (version + ":" + requestTrack).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                }
            });
            server.start();
        }

        String address() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
