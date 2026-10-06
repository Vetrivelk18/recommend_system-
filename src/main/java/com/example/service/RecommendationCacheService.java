package com.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Cache-aside store for the webhook's final picks, one entry per user.
 *
 * <p>Every call fails open: a read error reports a miss and a write error is dropped, so
 * an unreachable Redis costs a recompute but never costs a webhook - the same trade
 * OllamaReranker makes when it falls back to candidate order.
 *
 * <p>The value is a comma-joined id list rather than JSON. The ids are the only thing
 * worth caching (names are a cheap findAllById away), and a bare string needs no
 * serializer configuration beyond the StringRedisTemplate Spring Boot already provides.
 */
@Service
public class RecommendationCacheService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationCacheService.class);

    private static final String KEY_PREFIX = "reco:user:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;

    public RecommendationCacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<List<Integer>> get(Integer userId) {
        try {
            String value = redis.opsForValue().get(key(userId));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(parse(value));
        } catch (Exception e) {
            log.warn("recommendation cache read failed for user {} - treating as a miss: {}", userId, e.toString());
            return Optional.empty();
        }
    }

    public void put(Integer userId, List<Integer> productIds) {
        try {
            redis.opsForValue().set(key(userId), format(productIds), TTL);
        } catch (Exception e) {
            log.warn("recommendation cache write failed for user {} - the next poll recomputes: {}",
                    userId, e.toString());
        }
    }

    /** Called when a new search lands, so the previous search's picks can never be served. */
    public void evict(Integer userId) {
        try {
            redis.delete(key(userId));
        } catch (Exception e) {
            log.warn("recommendation cache evict failed for user {} - a stale entry may survive to its TTL: {}",
                    userId, e.toString());
        }
    }

    private String key(Integer userId) {
        return KEY_PREFIX + userId;
    }

    private String format(List<Integer> productIds) {
        return productIds.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private List<Integer> parse(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .toList();
    }
}
