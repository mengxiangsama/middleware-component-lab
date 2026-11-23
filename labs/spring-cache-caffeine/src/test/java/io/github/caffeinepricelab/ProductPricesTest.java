package io.github.caffeinepricelab;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static io.github.caffeinepricelab.PriceCacheConfiguration.PRODUCT_PRICES;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:price-cache-lab")
class ProductPricesTest {
    private static final long PRODUCT = 101L;
    private static final long OTHER_PRODUCT = 102L;
    private static final BigDecimal OLD_PRICE = new BigDecimal("19.90");
    private static final BigDecimal NEW_PRICE = new BigDecimal("24.90");
    private static final BigDecimal OTHER_PRICE = new BigDecimal("29.90");

    @Autowired
    private ProductPrices prices;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CacheManager cacheManager;
    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void resetLabData() {
        jdbc.update("MERGE INTO products (id, price) KEY (id) VALUES (?, ?), (?, ?)",
                PRODUCT, OLD_PRICE, OTHER_PRODUCT, OTHER_PRICE);
        cache().clear();
    }

    @Test
    void firstReadLoadsFromH2AndNextReadHitsCaffeine() {
        assertThat(cache().get(PRODUCT)).isNull();
        assertThat(cache().getNativeCache()).isInstanceOf(com.github.benmanes.caffeine.cache.Cache.class);
        assertThat(prices.findPrice(PRODUCT)).isEqualByComparingTo(OLD_PRICE);
        assertThat(cache().get(PRODUCT, BigDecimal.class)).isEqualByComparingTo(OLD_PRICE);

        // Bypass the application write path to distinguish a cache hit from a fresh database read.
        jdbc.update("UPDATE products SET price = ? WHERE id = ?", NEW_PRICE, PRODUCT);
        assertThat(databasePrice()).isEqualByComparingTo(NEW_PRICE);
        assertThat(prices.findPrice(PRODUCT)).isEqualByComparingTo(OLD_PRICE);
    }

    @Test
    void commitEvictsOnlyChangedProductAndNextReadLoadsNewPrice() {
        prices.findPrice(PRODUCT);
        prices.findPrice(OTHER_PRODUCT);

        transactions.executeWithoutResult(status -> {
            prices.changePrice(PRODUCT, NEW_PRICE);
            assertThat(databasePrice()).isEqualByComparingTo(NEW_PRICE);
            assertThat(cache().get(PRODUCT, BigDecimal.class)).isEqualByComparingTo(OLD_PRICE);
        });

        assertThat(cache().get(PRODUCT)).isNull();
        assertThat(cache().get(OTHER_PRODUCT, BigDecimal.class)).isEqualByComparingTo(OTHER_PRICE);
        assertThat(prices.findPrice(PRODUCT)).isEqualByComparingTo(NEW_PRICE);
        assertThat(cache().get(PRODUCT, BigDecimal.class)).isEqualByComparingTo(NEW_PRICE);
    }

    @Test
    void rollbackKeepsDatabaseAndCachedPrice() {
        prices.findPrice(PRODUCT);

        transactions.executeWithoutResult(status -> {
            prices.changePrice(PRODUCT, NEW_PRICE);
            assertThat(databasePrice()).isEqualByComparingTo(NEW_PRICE);
            status.setRollbackOnly();
        });

        assertThat(databasePrice()).isEqualByComparingTo(OLD_PRICE);
        assertThat(cache().get(PRODUCT, BigDecimal.class)).isEqualByComparingTo(OLD_PRICE);
    }

    @Test
    void readingInTransactionDoesNotCacheUncommittedPrice() {
        transactions.executeWithoutResult(status -> {
            prices.changePrice(PRODUCT, NEW_PRICE);
            assertThat(prices.findPrice(PRODUCT)).isEqualByComparingTo(NEW_PRICE);
            assertThat(cache().get(PRODUCT)).isNull();
            status.setRollbackOnly();
        });

        assertThat(prices.findPrice(PRODUCT)).isEqualByComparingTo(OLD_PRICE);
    }

    private Cache cache() {
        return cacheManager.getCache(PRODUCT_PRICES);
    }

    private BigDecimal databasePrice() {
        return jdbc.queryForObject("SELECT price FROM products WHERE id = ?", BigDecimal.class, PRODUCT);
    }
}
