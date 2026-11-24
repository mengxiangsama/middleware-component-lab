package io.github.middlewarelab.camelfile;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.http.HttpComponent;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileHttpImportTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sendsTheSameCsvOnceMoreAfter503AndArchivesOnlyAfterAcceptance() throws Exception {
        byte[] csv = "sku,quantity\nsku-42,7\n".getBytes(UTF_8);
        Path inbox = Files.createDirectory(temporaryDirectory.resolve("inbox"));
        Path source = Files.write(inbox.resolve("orders.csv"), csv);
        Path archived = inbox.resolve(".archive").resolve(source.getFileName());

        var secondRequestArrived = new CountDownLatch(1);
        var acceptSecondRequest = new CountDownLatch(1);
        List<Request> requests = new CopyOnWriteArrayList<>();
        var retried = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/inventory/import", exchange ->
                handleImport(exchange, requests, secondRequestArrived, acceptSecondRequest));

        var context = new DefaultCamelContext();
        try {
            server.start();
            context.getComponent("http", HttpComponent.class).setAutomaticRetriesDisabled(true);
            String fileEndpoint = "file:" + inbox.toUri().getRawPath()
                    + "?fileName=orders.csv&initialDelay=0&delay=100&move=.archive";
            String httpEndpoint = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/inventory/import?throwExceptionOnFailure=true";
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    onException(HttpOperationFailedException.class)
                            .onWhen(exchange -> exchange.getProperty(Exchange.EXCEPTION_CAUGHT)
                                    instanceof HttpOperationFailedException failure && failure.getStatusCode() == 503)
                            .maximumRedeliveries(1)
                            .redeliveryDelay(0)
                            .onRedelivery(exchange -> retried.incrementAndGet());

                    from(fileEndpoint)
                            .routeId("file-to-inventory")
                            .convertBodyTo(String.class, "UTF-8")
                            .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                            .setHeader(Exchange.CONTENT_TYPE, constant("text/csv; charset=UTF-8"))
                            .to(httpEndpoint);
                }
            });
            context.start();

            assertTrue(secondRequestArrived.await(10, SECONDS), "second HTTP request did not arrive");
            assertTrue(Files.isRegularFile(source));
            assertFalse(Files.exists(archived), "archive must wait for success");
            acceptSecondRequest.countDown();

            awaitArchive(archived);
            assertFalse(Files.exists(source));
            assertArrayEquals(csv, Files.readAllBytes(archived));
            assertEquals(1, retried.get());
            assertEquals(2, requests.size());
            for (var request : requests) {
                assertEquals("POST", request.method());
                assertTrue(request.contentType().startsWith("text/csv"));
                assertArrayEquals(csv, request.body());
            }
            System.out.println("HTTP 503 -> Camel redelivery -> HTTP 204: the same CSV was posted twice");
            System.out.println("orders.csv moved to .archive only after the receiver accepted it");
        } finally {
            acceptSecondRequest.countDown();
            try {
                context.stop();
            } finally {
                server.stop(0);
            }
        }
    }

    private static void handleImport(HttpExchange exchange, List<Request> requests,
                                     CountDownLatch secondRequestArrived, CountDownLatch acceptSecondRequest)
            throws IOException {
        try (exchange) {
            requests.add(new Request(exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestBody().readAllBytes()));
            int attempts = requests.size();
            if (attempts == 1) {
                exchange.sendResponseHeaders(503, -1);
            } else if (attempts == 2) {
                secondRequestArrived.countDown();
                try {
                    exchange.sendResponseHeaders(acceptSecondRequest.await(10, SECONDS) ? 204 : 500, -1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    exchange.sendResponseHeaders(500, -1);
                }
            } else {
                exchange.sendResponseHeaders(409, -1);
            }
        }
    }

    private static void awaitArchive(Path archived) throws InterruptedException {
        long limit = System.nanoTime() + SECONDS.toNanos(10);
        while (!Files.isRegularFile(archived) && System.nanoTime() < limit) {
            Thread.sleep(20);
        }
        assertTrue(Files.isRegularFile(archived), "file was not archived after HTTP success");
    }

    private record Request(String method, String contentType, byte[] body) {}
}
