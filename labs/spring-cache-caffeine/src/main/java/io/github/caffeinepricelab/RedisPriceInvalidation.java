package io.github.caffeinepricelab;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;

import static io.github.caffeinepricelab.PriceCacheConfiguration.PRODUCT_PRICES;
import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

@Configuration(proxyBeanMethods = false)
@Profile("redis-invalidation")
class RedisPriceInvalidation {
    private final StringRedisTemplate redis;
    private final String channel;

    RedisPriceInvalidation(StringRedisTemplate redis,
            @Value("${lab.price-invalidation.channel:lab.product-price.changed}") String channel) {
        this.redis = redis;
        this.channel = channel;
    }

    @TransactionalEventListener(phase = AFTER_COMMIT)
    void publishAfterCommit(ProductPrices.PriceChanged event) {
        redis.convertAndSend(channel, Long.toString(event.productId()));
    }

    @Bean
    RedisMessageListenerContainer priceInvalidationListener(
            RedisConnectionFactory connectionFactory, CacheManager cacheManager) {
        var localPrices = cacheManager.getCache(PRODUCT_PRICES);
        var listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(connectionFactory);
        listener.addMessageListener((message, pattern) -> {
            long productId = Long.parseLong(new String(message.getBody(), StandardCharsets.UTF_8));
            localPrices.evict(productId);
        }, new ChannelTopic(channel));
        return listener;
    }
}
