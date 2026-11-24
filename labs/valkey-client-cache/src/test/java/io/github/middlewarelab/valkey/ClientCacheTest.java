package io.github.middlewarelab.valkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TrackingArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.protocol.ProtocolVersion;
import io.lettuce.core.support.caching.CacheAccessor;
import io.lettuce.core.support.caching.CacheFrontend;
import io.lettuce.core.support.caching.ClientSideCaching;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class ClientCacheTest {
    private static final String HOST = "127.0.0.1";
    private static final String PRICE_KEY = "sku:42:price";
    private static final String OTHER_PRICE_KEY = "sku:43:price";

    @TempDir
    Path dataDirectory;

    @Test
    @Timeout(45)
    void reloadsOnlyInvalidatedPriceAfterWriteOnAnotherConnection() throws Exception {
        String binary = System.getenv("VALKEY_SERVER");
        assertTrue(binary != null && !binary.isBlank(), "Set VALKEY_SERVER to the valkey-server binary");
        int port;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
            port = reservation.getLocalPort();
        }

        Path log = dataDirectory.resolve("valkey.log");
        Process server = new ProcessBuilder(binary, "--bind", HOST, "--port", String.valueOf(port),
                "--save", "", "--appendonly", "no", "--dir", dataDirectory.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
            while (server.isAlive() && !Files.readString(log).contains("Ready to accept connections")
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(server.isAlive() && Files.readString(log).contains("Ready to accept connections"),
                    "Valkey did not start" + System.lineSeparator() + Files.readString(log));

            RedisURI uri = RedisURI.Builder.redis(HOST, port).withTimeout(Duration.ofSeconds(2)).build();
            RedisClient client = RedisClient.create(uri);
            client.setOptions(ClientOptions.builder().protocolVersion(ProtocolVersion.RESP3)
                    .autoReconnect(false).build());
            try (StatefulRedisConnection<String, String> writer = client.connect()) {
                verifyCache(client.connect(), writer.sync(), server.pid());
            } finally {
                client.shutdown();
            }
        } finally {
            server.destroy();
            if (!server.waitFor(5, TimeUnit.SECONDS)) {
                server.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void verifyCache(StatefulRedisConnection<String, String> reader,
            RedisCommands<String, String> writer, long processId) throws InterruptedException {
        String serverInfo = writer.info("server");
        assertEquals(String.valueOf(processId), infoValue(serverInfo, "process_id:"));
        String version = infoValue(serverInfo, "valkey_version:");
        assertNotEquals(reader.sync().clientId(), writer.clientId());
        assertEquals("OK", writer.set(PRICE_KEY, "19900"));
        assertEquals("OK", writer.set(OTHER_PRICE_KEY, "9900"));

        Map<String, String> localPrices = new ConcurrentHashMap<>();
        try (CacheFrontend<String, String> prices = ClientSideCaching.enable(
                CacheAccessor.forMap(localPrices), reader, TrackingArgs.Builder.enabled())) {
            long start = getCalls(writer);
            assertEquals("19900", prices.get(PRICE_KEY));
            assertEquals("9900", prices.get(OTHER_PRICE_KEY));
            assertEquals(start + 2, getCalls(writer));
            assertEquals("19900", prices.get(PRICE_KEY));
            assertEquals(start + 2, getCalls(writer), "warm read should not send GET");

            assertEquals("OK", writer.set(PRICE_KEY, "21900"));
            await(() -> !localPrices.containsKey(PRICE_KEY));
            assertEquals("9900", localPrices.get(OTHER_PRICE_KEY), "invalidate only the changed key");
            assertEquals("21900", prices.get(PRICE_KEY));
            assertEquals("9900", prices.get(OTHER_PRICE_KEY));
            assertEquals(start + 3, getCalls(writer), "only the edited price should be reloaded");
            System.out.printf("Valkey %s, GETs: warm-up +2, cache hit +0, edited price reload +1%n", version);
        }
    }

    private static void await(BooleanSupplier completed) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!completed.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(completed.getAsBoolean(), "did not observe the RESP3 invalidation");
    }

    private static long getCalls(RedisCommands<String, String> writer) {
        return writer.info("commandstats").lines()
                .filter(line -> line.startsWith("cmdstat_get:calls="))
                .mapToLong(line -> Long.parseLong(line.substring("cmdstat_get:calls=".length(), line.indexOf(','))))
                .findFirst()
                .orElse(0);
    }

    private static String infoValue(String info, String prefix) {
        return info.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing INFO server field " + prefix));
    }
}
