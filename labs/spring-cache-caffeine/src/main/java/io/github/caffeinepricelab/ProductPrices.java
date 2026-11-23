package io.github.caffeinepricelab;

import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static io.github.caffeinepricelab.PriceCacheConfiguration.PRODUCT_PRICES;

@Service
@CacheConfig(cacheNames = PRODUCT_PRICES)
public class ProductPrices {
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public ProductPrices(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    // A synchronized cache load fills the cache immediately, so lookups in transactions bypass it.
    @Cacheable(key = "#p0", sync = true,
            condition = "!T(org.springframework.transaction.support.TransactionSynchronizationManager).isActualTransactionActive()")
    public BigDecimal findPrice(long productId) {
        return jdbc.queryForObject("SELECT price FROM products WHERE id = ?", BigDecimal.class, productId);
    }

    @Transactional
    @CacheEvict(key = "#p0")
    public void changePrice(long productId, BigDecimal price) {
        int updated = jdbc.update("UPDATE products SET price = ? WHERE id = ?", price, productId);
        if (updated == 0) {
            throw new IllegalArgumentException("Unknown product: " + productId);
        }
        events.publishEvent(new PriceChanged(productId));
    }

    record PriceChanged(long productId) {
    }
}
