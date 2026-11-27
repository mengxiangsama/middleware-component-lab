package io.github.middlewarelab.otelhttp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpPropagationTest {
    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void continuesTraceAcrossHttp() throws Exception {
        InMemorySpanExporter clientExporter = InMemorySpanExporter.create();
        try (OpenTelemetrySdk clientSdk = telemetry("orders", clientExporter);
             InventoryServer server = new InventoryServer()) {
            HttpResponse<String> response = new InventoryHttpTracing(clientSdk).getInventory(CLIENT, server.uri);
            String traceparent = server.receivedTraceparent.get(5, SECONDS);
            SpanData client = onlySpan(clientExporter, "orders", SpanKind.CLIENT);
            SpanData inventory = onlySpan(server.exporter, "inventory", SpanKind.SERVER);

            assertEquals(200, response.statusCode());
            assertTrue(client.getSpanContext().isValid());
            assertFalse(client.getParentSpanContext().isValid());
            assertTrue(client.getSpanContext().isSampled());
            String flags = client.getSpanContext().getTraceFlags().asHex();
            assertEquals("00-" + client.getTraceId() + "-" + client.getSpanId() + "-" + flags, traceparent);
            assertEquals(client.getTraceId(), inventory.getTraceId());
            assertNotEquals(client.getSpanId(), inventory.getSpanId());
            assertEquals(client.getSpanId(), inventory.getParentSpanId());
            assertTrue(inventory.getParentSpanContext().isRemote());
            assertEquals(client.getSpanContext().getTraceFlags(), inventory.getParentSpanContext().getTraceFlags());
            assertEquals(inventory.getTraceId(), response.body());
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "00-00000000000000000000000000000000-0123456789abcdef-01")
    void startsNewTraceWhenTraceparentIsAbsentOrInvalid(String value) throws Exception {
        try (InventoryServer server = new InventoryServer()) {
            HttpRequest.Builder request = HttpRequest.newBuilder(server.uri)
                    .timeout(Duration.ofSeconds(5))
                    .GET();
            if (value != null) {
                request.setHeader("traceparent", value);
            }

            HttpResponse<String> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(value, server.receivedTraceparent.get(5, SECONDS));
            SpanData inventory = onlySpan(server.exporter, "inventory", SpanKind.SERVER);

            assertEquals(200, response.statusCode());
            assertTrue(inventory.getSpanContext().isValid());
            assertFalse(inventory.getParentSpanContext().isValid());
            assertEquals(inventory.getTraceId(), response.body());
        }
    }

    private static OpenTelemetrySdk telemetry(String service, InMemorySpanExporter exporter) {
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(Resource.create(Attributes.of(SERVICE_NAME, service)))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        return OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    private static SpanData onlySpan(InMemorySpanExporter exporter, String service, SpanKind kind) {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);
        assertEquals(service, span.getResource().getAttribute(SERVICE_NAME));
        assertEquals(kind, span.getKind());
        return span;
    }

    private static void respondWithCurrentTraceId(HttpExchange exchange) throws IOException {
        byte[] traceId = Span.current().getSpanContext().getTraceId().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, traceId.length);
        exchange.getResponseBody().write(traceId);
    }

    private static final class InventoryServer implements AutoCloseable {
        private final HttpServer server;
        private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
        private final OpenTelemetrySdk sdk = telemetry("inventory", exporter);
        private final CompletableFuture<String> receivedTraceparent = new CompletableFuture<>();
        private final URI uri;

        InventoryServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/inventory");
            HttpHandler instrumented = new InventoryHttpTracing(sdk)
                    .instrumentInventory(HttpPropagationTest::respondWithCurrentTraceId);
            server.createContext("/inventory", exchange -> {
                String header = exchange.getRequestHeaders().getFirst("traceparent");
                try (exchange) {
                    instrumented.handle(exchange);
                } catch (IOException | RuntimeException e) {
                    receivedTraceparent.completeExceptionally(e);
                    throw e;
                }
                // SimpleSpanProcessor has exported the ended server span before this signal.
                receivedTraceparent.complete(header);
            });
            server.start();
        }

        @Override
        public void close() {
            server.stop(0);
            sdk.close();
        }
    }
}
