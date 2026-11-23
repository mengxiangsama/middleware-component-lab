package io.github.caffeinepricelab;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.caffeinepricelab.PriceCacheConfiguration.PRODUCT_PRICES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RedisPriceCacheIT {
    private static final long PRODUCT = 101L;
    private static final long OTHER_PRODUCT = 102L;
    private static final BigDecimal OLD_PRICE = new BigDecimal("19.90");
    private static final BigDecimal NEW_PRICE = new BigDecimal("24.90");
    private static final BigDecimal OTHER_PRICE = new BigDecimal("29.90");

    @Test
    void committedPriceEvictsBothInstancesAndEachLoadsOnce() throws Exception {
        int port = Integer.parseInt(System.getProperty("lab.redis.port", "16379"));
        String runId = UUID.randomUUID().toString();
        String jdbcUrl = "jdbc:h2:mem:cluster-price-" + runId;
        String channel = "lab.product-price.changed." + runId;

        try (var instanceA = start(jdbcUrl, channel, port, "always");
             var instanceB = start(jdbcUrl, channel, port, "never")) {
            var jdbc = instanceA.getBean(JdbcTemplate.class);
            jdbc.update("INSERT INTO products (id, price) VALUES (?, ?), (?, ?)",
                    PRODUCT, OLD_PRICE, OTHER_PRODUCT, OTHER_PRICE);

            var pricesA = instanceA.getBean(ProductPrices.class);
            var pricesB = instanceB.getBean(ProductPrices.class);
            var cacheA = cache(instanceA);
            var cacheB = cache(instanceB);
            assertThat(cacheA.getNativeCache()).isNotSameAs(cacheB.getNativeCache());
            for (var prices : List.of(pricesA, pricesB)) {
                assertThat(prices.findPrice(PRODUCT)).isEqualByComparingTo(OLD_PRICE);
                assertThat(prices.findPrice(OTHER_PRODUCT)).isEqualByComparingTo(OTHER_PRICE);
            }

            instanceA.getBean(TransactionTemplate.class).executeWithoutResult(tx -> {
                pricesA.changePrice(PRODUCT, NEW_PRICE);
                tx.setRollbackOnly();
            });
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(cacheA.get(PRODUCT, BigDecimal.class)).isEqualByComparingTo(OLD_PRICE);
                assertThat(cacheB.get(PRODUCT, BigDecimal.class)).isEqualByComparingTo(OLD_PRICE);
            });

            pricesA.changePrice(PRODUCT, NEW_PRICE);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(cacheA.get(PRODUCT)).isNull();
                assertThat(cacheB.get(PRODUCT)).isNull();
            });
            for (var cache : List.of(cacheA, cacheB)) {
                assertThat(cache.get(OTHER_PRODUCT, BigDecimal.class)).isEqualByComparingTo(OTHER_PRICE);
            }

            long beforeA = loads(cacheA);
            long beforeB = loads(cacheB);
            readConcurrently(pricesA, pricesB);
            assertThat(loads(cacheA) - beforeA).isEqualTo(1);
            assertThat(loads(cacheB) - beforeB).isEqualTo(1);
        }
    }

    private void readConcurrently(ProductPrices... instances) throws Exception {
        int readersPerInstance = 12;
        int count = readersPerInstance * instances.length;
        var executor = Executors.newFixedThreadPool(count);
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<BigDecimal>>(count);
        try {
            for (int reader = 0; reader < readersPerInstance; reader++) {
                for (var prices : instances) {
                    results.add(executor.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        return prices.findPrice(PRODUCT);
                    }));
                }
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isEqualByComparingTo(NEW_PRICE);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private ConfigurableApplicationContext start(String jdbcUrl, String channel, int port, String initMode) {
        return new SpringApplicationBuilder(PriceCacheLabApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("redis-invalidation")
                .run("--spring.datasource.url=" + jdbcUrl,
                        "--spring.sql.init.mode=" + initMode,
                        "--spring.data.redis.host=127.0.0.1",
                        "--spring.data.redis.port=" + port,
                        "--lab.price-invalidation.channel=" + channel);
    }

    private Cache cache(ConfigurableApplicationContext context) {
        return context.getBean(CacheManager.class).getCache(PRODUCT_PRICES);
    }

    private long loads(Cache cache) {
        var caffeine = (com.github.benmanes.caffeine.cache.Cache<?, ?>) cache.getNativeCache();
        return caffeine.stats().loadSuccessCount();
    }
}
