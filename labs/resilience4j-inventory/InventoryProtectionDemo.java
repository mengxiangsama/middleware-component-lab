import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.functions.CheckedSupplier;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class InventoryProtectionDemo {
    public static void main(String[] args) throws Throwable {
        AtomicInteger status = new AtomicInteger(503);
        AtomicInteger httpCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stock", exchange -> {
            try (exchange) {
                httpCalls.incrementAndGet();
                int responseStatus = status.get();
                if (responseStatus == 503) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                byte[] body = "42".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            runScenario(server.getAddress().getPort(), status, httpCalls);
        } finally {
            server.stop(0);
        }
    }

    private static void runScenario(int port, AtomicInteger status, AtomicInteger httpCalls)
            throws Throwable {
        // Advance the breaker clock without a wall-clock sleep.
        AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
        InstantSource testTime = now::get;
        CircuitBreaker breaker = CircuitBreaker.of("inventory", CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(100)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .permittedNumberOfCallsInHalfOpenState(2)
                .clock(testTime.withZone(ZoneOffset.UTC))
                .build());
        Retry retry = Retry.of("inventory", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(InventoryUnavailable.class)
                .build());
        AtomicInteger retryEvents = new AtomicInteger();
        retry.getEventPublisher().onRetry(event -> retryEvents.incrementAndGet());

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/stock"))
                .timeout(Duration.ofSeconds(2))
                .build();
        CheckedSupplier<Integer> stockOverHttp = () -> {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return switch (response.statusCode()) {
                case 200 -> Integer.parseInt(response.body());
                case 503 -> throw new InventoryUnavailable();
                default -> throw new IllegalStateException("Unexpected HTTP " + response.statusCode());
            };
        };
        CheckedSupplier<Integer> protectedStock = Retry.decorateCheckedSupplier(retry,
                CircuitBreaker.decorateCheckedSupplier(breaker, stockOverHttp));

        expectRejected(protectedStock);
        require(breaker.getState() == CircuitBreaker.State.OPEN, "Breaker did not open");
        require(httpCalls.get() == 2 && retryEvents.get() == 2,
                "Expected two HTTP 503s, then a rejected attempt");
        require(breaker.getMetrics().getNumberOfNotPermittedCalls() == 1,
                "Third attempt should be rejected");
        System.out.println("OPEN: HTTP calls=2; third attempt blocked");

        expectRejected(protectedStock);
        require(httpCalls.get() == 2 && retryEvents.get() == 2,
                "An open circuit must add neither HTTP calls nor retries");
        require(breaker.getMetrics().getNumberOfNotPermittedCalls() == 2,
                "Expected one rejection for the new request");
        System.out.println("OPEN: next request rejected once; HTTP calls still 2");

        status.set(200);
        now.updateAndGet(time -> time.plusSeconds(6));
        require(protectedStock.get() == 42 && breaker.getState() == CircuitBreaker.State.HALF_OPEN,
                "One successful probe must leave the circuit half open");
        System.out.println("HALF_OPEN: first probe returned 42");
        require(protectedStock.get() == 42 && breaker.getState() == CircuitBreaker.State.CLOSED,
                "Second successful probe must close the circuit");
        require(httpCalls.get() == 4 && retryEvents.get() == 2,
                "Expected two failure requests and two successful probes over HTTP");
        System.out.println("CLOSED: second probe returned 42; total HTTP calls=4");
    }

    private static void expectRejected(CheckedSupplier<Integer> call) throws Throwable {
        try {
            call.get();
            throw new AssertionError("Expected CallNotPermittedException");
        } catch (CallNotPermittedException expected) {
            // The breaker rejected this call before the HTTP request.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class InventoryUnavailable extends RuntimeException {
    }
}
