package com.example.service;

import com.example.config.RateLimitProperties;
import com.example.dto.RateLimitDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fixed-window counters in Redis, one key per (bucket, identity) pair.
 *
 * <p>Reuses the StringRedisTemplate Spring Boot already autoconfigures - the same one
 * SearchCacheService and RecommendationCacheService inject. There is no second Redis
 * configuration, and the "rate-limit:" prefix cannot collide with "search:q:" or
 * "reco:user:".
 *
 * <p>Fails open, exactly like the two caches: an unreachable Redis must not take the
 * API down. The cost of that choice is that a broken Redis silently removes all
 * limiting, with nothing user-visible to say so - hence the distinct WARN below, which
 * is the string to grep for when the app is unexpectedly easy to hammer.
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private static final String KEY_PREFIX = "rate-limit:";

    /**
     * INCR and PEXPIRE in one atomic call.
     *
     * <p>Doing these as two commands has a real failure mode: if the PEXPIRE is lost
     * after the INCR lands, the key never expires and that identity is blocked forever -
     * a fail-closed hole inside a fail-open design.
     *
     * <p>The TTL is re-applied whenever PTTL reports none (-1 for a key without a TTL,
     * -2 for one that vanished between the two calls), so a key that somehow lost its
     * expiry heals on the next request instead of wedging.
     */
    private static final RedisScript<List> COUNT = RedisScript.of("""
            local n = redis.call('INCR', KEYS[1])
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
              ttl = tonumber(ARGV[1])
            end
            return { n, ttl }
            """, List.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;

    public RateLimitService(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * @param bucketName one of the configured ratelimit.buckets.* names
     * @param identity   "{userId}" for an authenticated caller, "ip:{address}" otherwise
     */
    public RateLimitDecision check(String bucketName, String identity) {
        RateLimitProperties.Bucket bucket = properties.getBuckets().get(bucketName);
        if (bucket == null || bucket.getWindow() == null || bucket.getLimit() <= 0) {
            return RateLimitDecision.unenforced();
        }

        String key = KEY_PREFIX + bucketName + ":" + identity;
        long windowMillis = bucket.getWindow().toMillis();

        try {
            List<?> result = redis.execute(COUNT, List.of(key), String.valueOf(windowMillis));
            if (result == null || result.size() < 2) {
                log.warn("rate limit script returned no count for {} - allowing request", key);
                return RateLimitDecision.unenforced();
            }

            long count = ((Number) result.get(0)).longValue();
            long ttlMillis = ((Number) result.get(1)).longValue();

            boolean allowed = count <= bucket.getLimit();
            long remaining = Math.max(0, bucket.getLimit() - count);
            // Ceiling, so a caller told to wait never retries while the window is still open.
            long retryAfter = Math.max(1, (ttlMillis + 999) / 1000);

            if (!allowed) {
                log.info("rate limit exceeded: {} ({}/{} in {})",
                        key, count, bucket.getLimit(), bucket.getWindow());
            }
            return new RateLimitDecision(allowed, bucket.getLimit(), remaining, retryAfter);

        } catch (Exception e) {
            log.warn("rate limit check failed for {} - allowing request: {}", key, e.toString());
            return RateLimitDecision.unenforced();
        }
    }
}
