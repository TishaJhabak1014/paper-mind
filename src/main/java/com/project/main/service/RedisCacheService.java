package com.project.main.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Thin, fail-open Redis cache wrapper. Cache outages must not stop paper discovery. */
@Service
public class RedisCacheService {
    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);
    private final StringRedisTemplate redis;

    public RedisCacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String get(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException exception) {
            log.warn("Redis cache read failed; continuing without cache: {}", exception.getMessage());
            return null;
        }
    }

    public void put(String key, String value, Duration ttl) {
        if (value == null || ttl == null || ttl.isZero() || ttl.isNegative()) return;
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (RuntimeException exception) {
            log.warn("Redis cache write failed; continuing without cache: {}", exception.getMessage());
        }
    }

    public static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
