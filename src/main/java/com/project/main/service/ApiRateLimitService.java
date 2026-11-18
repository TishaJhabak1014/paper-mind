package com.project.main.service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Enforces a shared minimum start interval per upstream provider. */
@Service
public class ApiRateLimitService {
    private static final Logger log = LoggerFactory.getLogger(ApiRateLimitService.class);
    private final StringRedisTemplate redis;
    private final ConcurrentMap<String, Long> localNextAllowed = new ConcurrentHashMap<>();

    public ApiRateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void acquire(String provider, long minimumIntervalMillis) {
        long interval = Math.max(0, minimumIntervalMillis);
        if (interval == 0) return;
        String key = "papermind:rate-limit:" + provider;
        String cooldownKey = "papermind:rate-limit:cooldown:" + provider;
        while (true) {
            Boolean acquired;
            Long cooldownMillis;
            try {
                cooldownMillis = redis.getExpire(cooldownKey, TimeUnit.MILLISECONDS);
                acquired = cooldownMillis != null && cooldownMillis > 0
                        ? Boolean.FALSE
                        : redis.opsForValue().setIfAbsent(key, "1", Duration.ofMillis(interval));
            } catch (RuntimeException exception) {
                log.warn("Redis rate limiter unavailable; using this instance's limiter: {}", exception.getMessage());
                acquireLocally(provider, interval);
                return;
            }
            if (cooldownMillis != null && cooldownMillis > 0) {
                String providerName = provider.equals("semantic-scholar") ? "Semantic Scholar" : "Gemini";
                long retrySeconds = (cooldownMillis + 999) / 1_000;
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        providerName + " cooldown is active. Retry in about " + retrySeconds + " seconds.");
            }
            if (Boolean.TRUE.equals(acquired)) return;
            pause(Math.min(100, interval));
        }
    }

    public void applyCooldown(String provider, long cooldownMillis) {
        if (cooldownMillis <= 0) return;
        try {
            redis.opsForValue().set("papermind:rate-limit:cooldown:" + provider, "1", Duration.ofMillis(cooldownMillis));
        } catch (RuntimeException exception) {
            log.warn("Could not share upstream cooldown through Redis: {}", exception.getMessage());
        }
    }

    private void acquireLocally(String provider, long interval) {
        synchronized (localNextAllowed) {
            long now = System.currentTimeMillis();
            long next = localNextAllowed.getOrDefault(provider, 0L);
            if (next > now) pause(next - now);
            localNextAllowed.put(provider, System.currentTimeMillis() + interval);
        }
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The upstream request was interrupted.", exception);
        }
    }
}
