package com.wherestrangersmeet.backend.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                "presignedUrls",           // S3 URL cache
                "userIdCache",             // Firebase UID → User ID
                "firebaseUidCache",        // User ID → Firebase UID
                "userCache",               // Full user objects
                "userByFirebaseUidCache"   // User by Firebase UID
        );

        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES) // User data expires after 10 min
                .maximumSize(10000)                     // Max 10k entries total
                .recordStats());                        // Enable cache statistics

        return cacheManager;
    }
}
