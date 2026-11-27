package io.github.middlewarelab.otelhttp;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpHandler;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class InventoryHttpTracing {
    private static final TextMapGetter<Headers> REQUEST_HEADERS = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers headers) {
            return headers.keySet();
        }

        @Override
        public String get(Headers headers, String key) {
            return headers == null ? null : headers.getFirst(key);
        }
    };

    private final Tracer tracer;
    private final TextMapPropagator propagator;

    InventoryHttpTracing(OpenTelemetry telemetry) {
        tracer = telemetry.getTracer("inventory-http-lab");
        propagator = telemetry.getPropagators().getTextMapPropagator();
    }

    HttpResponse<String> getInventory(HttpClient client, URI uri) throws IOException, InterruptedException {
        Span clientSpan = tracer.spanBuilder("GET").setSpanKind(SpanKind.CLIENT).startSpan();
        try (Scope scope = clientSpan.makeCurrent()) {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET();
            propagator.inject(Context.current(), request, HttpRequest.Builder::setHeader);
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException | RuntimeException e) {
            clientSpan.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            clientSpan.end();
        }
    }

    HttpHandler instrumentInventory(HttpHandler handler) {
        return exchange -> {
            Context remote = propagator.extract(Context.root(), exchange.getRequestHeaders(), REQUEST_HEADERS);
            Span serverSpan = tracer.spanBuilder("GET /inventory")
                    .setSpanKind(SpanKind.SERVER)
                    .setParent(remote)
                    .startSpan();
            try (Scope scope = remote.with(serverSpan).makeCurrent()) {
                handler.handle(exchange);
            } catch (IOException | RuntimeException e) {
                serverSpan.setStatus(StatusCode.ERROR);
                throw e;
            } finally {
                serverSpan.end();
            }
        };
    }
}
