package io.github.middlewarelab.curator;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.InstanceSpec;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.Version;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreOrderLimitCacheTest {
    private static final String PATH = "/stores/42/order-limit";

    @Test
    void readerObservesInitialLimitUpdateAndDeletionFromAnotherSession() throws Exception {
        var spec = new InstanceSpec(null, -1, -1, -1, true, -1, -1, -1,
                Map.of("clientPortAddress", "127.0.0.1"));
        try (var server = new TestingServer(spec, true);
             var writer = client(server.getConnectString());
             var reader = client(server.getConnectString())) {
            writer.start();
            reader.start();
            assertTrue(writer.blockUntilConnected(10, SECONDS));
            assertTrue(reader.blockUntilConnected(10, SECONDS));
            writer.create().creatingParentsIfNeeded().forPath(PATH, "5".getBytes(UTF_8));

            try (var cache = CuratorCache.build(reader, PATH, CuratorCache.Options.SINGLE_NODE_CACHE)) {
                var initialized = new CountDownLatch(1);
                var changed = new CompletableFuture<ChildData>();
                var deleted = new CompletableFuture<ChildData>();
                cache.listenable().addListener(CuratorCacheListener.builder()
                        .forInitialized(initialized::countDown)
                        .forChanges((oldData, newData) -> changed.complete(newData))
                        .forDeletes(deleted::complete)
                        .build());
                cache.start();

                assertTrue(initialized.await(10, SECONDS), "cache did not initialize");
                assertEquals("5", value(cache.get(PATH).orElseThrow()));

                writer.setData().withVersion(0).forPath(PATH, "8".getBytes(UTF_8));
                var updated = changed.get(10, SECONDS);
                assertEquals(PATH, updated.getPath());
                assertEquals(1, updated.getStat().getVersion());
                assertEquals("8", value(cache.get(PATH).orElseThrow()));

                writer.delete().withVersion(1).forPath(PATH);
                assertEquals("8", value(deleted.get(10, SECONDS)));
                assertTrue(cache.get(PATH).isEmpty());
                System.out.println("ZooKeeper " + Version.getVersion()
                        + ": store 42 local order limit 5 -> 8 -> absent");
            }
        }
    }

    private CuratorFramework client(String endpoint) {
        return CuratorFrameworkFactory.newClient(endpoint, new ExponentialBackoffRetry(100, 3));
    }

    private String value(ChildData data) {
        return new String(data.getData(), UTF_8);
    }
}
