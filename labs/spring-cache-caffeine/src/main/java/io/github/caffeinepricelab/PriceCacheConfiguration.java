package io.github.caffeinepricelab;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableCaching
class PriceCacheConfiguration {
    static final String PRODUCT_PRICES = "productPrices";

    @Bean
    CacheManager cacheManager() {
        var caffeine = new CaffeineCacheManager(PRODUCT_PRICES);
        caffeine.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofMinutes(5)));
        return new TransactionAwareCacheManagerProxy(caffeine);
    }
}
