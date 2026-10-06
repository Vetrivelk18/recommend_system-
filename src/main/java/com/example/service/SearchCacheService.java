package com.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Cache-aside store for the RRF pool behind a search box query.
 *
 * <p>Keyed by the query text and nothing else, because SearchRepository.rrf takes no
 * user: the same words produce the same ranking for everybody, so one shopper's search
 * warms it for the next. A per-user key would recompute the identical pool per person
 * and thrash whenever someone alternates between two queries.
 *
 * <p>Ten minutes rather than the recommendation cache's thirty - a search is answered
 * while the shopper waits, so stock changes should surface sooner here.
 */
@Service
public class SearchCacheService {

    private static final Logger log = LoggerFactory.getLogger(SearchCacheService.class);

    private static final String KEY_PREFIX = "search:q:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    public SearchCacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<List<Integer>> get(String queryText) {
        try {
            String value = redis.opsForValue().get(key(queryText));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(parse(value));
        } catch (Exception e) {
            log.warn("search cache read failed - treating as a miss: {}", e.toString());
            return Optional.empty();
        }
    }

    public void put(String queryText, List<Integer> productIds) {
        try {
            redis.opsForValue().set(key(queryText), format(productIds), TTL);
        } catch (Exception e) {
            log.warn("search cache write failed - the next search recomputes: {}", e.toString());
        }
    }

    /** Trimmed and lowercased, so "Wheat" and " wheat " share one entry. */
    private String key(String queryText) {
        return KEY_PREFIX + queryText.trim().toLowerCase(Locale.ROOT);
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
