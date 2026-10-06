package com.example;

import com.example.config.RateLimitProperties;
import com.example.filter.RateLimitFilter;
import com.example.service.JwtService;
import com.example.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the decision flow and the Redis contract around it: bucket mapping, identity
 * resolution, the 429 response, and fail-open.
 *
 * <p>Redis is a hand-written stand-in rather than a mock, so the counters behave like
 * real fixed-window counters and the keys can be asserted. What this therefore does
 * NOT cover is the Lua script itself - INCR/PTTL/PEXPIRE atomicity needs a real Redis.
 */
class RateLimitFilterTest {

    private static final String SECRET = "test-only-secret-at-least-32-bytes-long-for-hs256";

    private FakeRedis redis;
    private RateLimitProperties properties;
    private RateLimitFilter filter;
    private JwtService jwt;

    @BeforeEach
    void setUp() {
        redis = new FakeRedis();
        properties = props();
        jwt = new JwtService(SECRET, 120);
        filter = new RateLimitFilter(new RateLimitService(redis, properties), jwt, properties);
    }

    private RateLimitProperties props() {
        RateLimitProperties p = new RateLimitProperties();
        Map<String, RateLimitProperties.Bucket> buckets = new LinkedHashMap<>();
        buckets.put("search", bucket(30, Duration.ofMinutes(1)));
        buckets.put("recommendations", bucket(10, Duration.ofMinutes(1)));
        buckets.put("auth", bucket(5, Duration.ofMinutes(10)));
        p.setBuckets(buckets);
        return p;
    }

    private RateLimitProperties.Bucket bucket(int limit, Duration window) {
        RateLimitProperties.Bucket b = new RateLimitProperties.Bucket();
        b.setLimit(limit);
        b.setWindow(window);
        return b;
    }

    /** One request through the filter. Returns the response; chain.getRequest() != null means it passed. */
    private Result call(String uri, String token, String ip, Map<String, String> headers) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setRemoteAddr(ip == null ? "10.0.0.1" : ip);
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        if (headers != null) {
            headers.forEach(request::addHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(response, chain.getRequest() != null);
    }

    private Result call(String uri) throws Exception {
        return call(uri, null, null, null);
    }

    private record Result(MockHttpServletResponse response, boolean reachedChain) {
    }

    @Test
    @DisplayName("under the limit the request reaches the chain")
    void allowsUnderLimit() throws Exception {
        for (int i = 1; i <= 5; i++) {
            Result r = call("/api/auth/login");
            assertThat(r.reachedChain()).as("request " + i).isTrue();
            assertThat(r.response().getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("the request past the limit is rejected and never reaches the chain")
    void rejectsOverLimit() throws Exception {
        for (int i = 1; i <= 5; i++) {
            call("/api/auth/login");
        }

        Result sixth = call("/api/auth/login");

        assertThat(sixth.reachedChain()).isFalse();
        assertThat(sixth.response().getStatus()).isEqualTo(429);
        assertThat(sixth.response().getHeader("Retry-After")).isEqualTo("60");
        assertThat(sixth.response().getHeader("X-RateLimit-Limit")).isEqualTo("5");
        assertThat(sixth.response().getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        // The charset matters: without it Tomcat appends ISO-8859-1 to a JSON response.
        assertThat(sixth.response().getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(sixth.response().getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(sixth.response().getContentAsString())
                .isEqualTo("{\"error\":\"rate limit exceeded\",\"endpoint\":\"auth\","
                        + "\"limit\":5,\"retryAfterSeconds\":60}");
    }

    @Test
    @DisplayName("Retry-After is the remaining window, rounded up")
    void retryAfterRoundsUp() throws Exception {
        redis.ttlMillis = 23_400;
        for (int i = 1; i <= 6; i++) {
            call("/api/auth/login");
        }
        assertThat(call("/api/auth/login").response().getHeader("Retry-After")).isEqualTo("24");
    }

    @Test
    @DisplayName("each path maps to its own bucket and its own key")
    void bucketsAreSeparate() throws Exception {
        call("/api/search");
        call("/api/recommendations/206220");
        call("/api/auth/login");

        assertThat(redis.keysSeen).containsExactly(
                "rate-limit:search:ip:10.0.0.1",
                "rate-limit:recommendations:ip:10.0.0.1",
                "rate-limit:auth:ip:10.0.0.1");
    }

    @Test
    @DisplayName("unlisted paths never touch Redis")
    void unlistedPathsAreNotCounted() throws Exception {
        assertThat(call("/api/onboarding/days").reachedChain()).isTrue();
        assertThat(call("/api/dashboard/206220").reachedChain()).isTrue();
        assertThat(redis.keysSeen).isEmpty();
    }

    @Test
    @DisplayName("a valid token keys the counter by user id, not by IP")
    void keysByUserWhenTokenPresent() throws Exception {
        call("/api/search", jwt.issue(206220, "Asha"), "10.0.0.1", null);
        assertThat(redis.keysSeen).containsExactly("rate-limit:search:206220");
    }

    @Test
    @DisplayName("two users on one IP get separate allowances")
    void usersOnOneIpDoNotShare() throws Exception {
        String asha = jwt.issue(206220, "Asha");
        String usha = jwt.issue(206222, "usha");

        for (int i = 1; i <= 30; i++) {
            call("/api/search", asha, "10.0.0.1", null);
        }

        assertThat(call("/api/search", asha, "10.0.0.1", null).response().getStatus()).isEqualTo(429);
        assertThat(call("/api/search", usha, "10.0.0.1", null).reachedChain()).isTrue();
    }

    @Test
    @DisplayName("an expired token falls back to IP rather than rejecting the request")
    void expiredTokenFallsBackToIp() throws Exception {
        String expired = new JwtService(SECRET, -1).issue(206220, "Asha");

        Result r = call("/api/search", expired, "10.0.0.1", null);

        assertThat(r.reachedChain()).isTrue();
        assertThat(redis.keysSeen).containsExactly("rate-limit:search:ip:10.0.0.1");
    }

    @Test
    @DisplayName("a token signed with the wrong key falls back to IP")
    void forgedTokenFallsBackToIp() throws Exception {
        String forged = new JwtService("a-different-secret-also-32-bytes-long-at-least", 120)
                .issue(999999, "attacker");

        call("/api/search", forged, "10.0.0.1", null);

        assertThat(redis.keysSeen).containsExactly("rate-limit:search:ip:10.0.0.1");
    }

    @Test
    @DisplayName("the caller-supplied userId parameter is ignored for keying")
    void userIdParameterCannotBeUsedToEvade() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/search");
        request.setRequestURI("/api/search");
        request.setRemoteAddr("10.0.0.1");
        request.setParameter("userId", "12345");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(redis.keysSeen).containsExactly("rate-limit:search:ip:10.0.0.1");
    }

    @Test
    @DisplayName("X-Forwarded-For is ignored unless trusted")
    void forwardedHeaderIgnoredByDefault() throws Exception {
        call("/api/search", null, "10.0.0.1", Map.of("X-Forwarded-For", "203.0.113.9"));
        assertThat(redis.keysSeen).containsExactly("rate-limit:search:ip:10.0.0.1");
    }

    @Test
    @DisplayName("X-Forwarded-For is used when trusted, taking the left-most hop")
    void forwardedHeaderUsedWhenTrusted() throws Exception {
        properties.setTrustForwardedHeader(true);
        call("/api/search", null, "10.0.0.1", Map.of("X-Forwarded-For", "203.0.113.9, 70.41.3.18"));
        assertThat(redis.keysSeen).containsExactly("rate-limit:search:ip:203.0.113.9");
    }

    @Test
    @DisplayName("a Redis failure allows the request and does not propagate")
    void failsOpen() throws Exception {
        redis.fail = true;

        for (int i = 1; i <= 20; i++) {
            Result r = call("/api/auth/login");
            assertThat(r.reachedChain()).as("request " + i).isTrue();
            assertThat(r.response().getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("disabling the limiter skips Redis entirely")
    void disabledSkipsRedis() throws Exception {
        properties.setEnabled(false);

        for (int i = 1; i <= 50; i++) {
            assertThat(call("/api/auth/login").reachedChain()).isTrue();
        }
        assertThat(redis.keysSeen).isEmpty();
    }

    @Test
    @DisplayName("an unconfigured bucket name is not enforced")
    void unconfiguredBucketIsNotEnforced() throws Exception {
        properties.getBuckets().remove("auth");

        for (int i = 1; i <= 20; i++) {
            assertThat(call("/api/auth/login").reachedChain()).isTrue();
        }
        assertThat(redis.keysSeen).isEmpty();
    }

    /**
     * Stands in for Redis with real fixed-window semantics, so counts accumulate per key
     * the way the live counters do. Only execute() is used by RateLimitService.
     */
    private static class FakeRedis extends StringRedisTemplate {

        private final Map<String, Long> counts = new HashMap<>();
        private final List<String> keysSeen = new ArrayList<>();
        private boolean fail;
        private long ttlMillis = 60_000;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            String key = keys.get(0);
            keysSeen.add(key);
            if (fail) {
                throw new RedisConnectionFailureException("stand-in Redis is down");
            }
            long n = counts.merge(key, 1L, Long::sum);
            return (T) List.of(n, ttlMillis);
        }
    }
}
