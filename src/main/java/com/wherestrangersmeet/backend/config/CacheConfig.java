package com.wherestrangersmeet.backend.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("presignedUrls");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(50, TimeUnit.MINUTES) // URLs valid for 1hr, cache for 50min
                .maximumSize(10000) // Max 10k cached URLs
                .recordStats()); // Enable cache statistics for monitoring
        return cacheManager;
    }
}
