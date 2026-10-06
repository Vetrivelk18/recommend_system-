package com.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Limits live in application.properties rather than in the filter, so a limit can be
 * retuned without a rebuild.
 *
 * <p>Buckets are keyed by a short name the filter derives from the request path
 * ("search", "recommendations", "auth"). A path with no bucket is not limited at all -
 * that is the default for anything under /api that is not listed, which today means
 * the onboarding dropdowns and the dashboard.
 */
@ConfigurationProperties(prefix = "ratelimit")
public class RateLimitProperties {

    /** Master switch. False skips Redis entirely - the filter becomes a pass-through. */
    private boolean enabled = true;

    /**
     * Whether X-Forwarded-For may be believed. It is client-settable, so this is only
     * safe behind a proxy that overwrites the header. Off by default: a spoofable
     * identifier is worse than a coarse one.
     */
    private boolean trustForwardedHeader = false;

    private Map<String, Bucket> buckets = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isTrustForwardedHeader() {
        return trustForwardedHeader;
    }

    public void setTrustForwardedHeader(boolean trustForwardedHeader) {
        this.trustForwardedHeader = trustForwardedHeader;
    }

    public Map<String, Bucket> getBuckets() {
        return buckets;
    }

    public void setBuckets(Map<String, Bucket> buckets) {
        this.buckets = buckets;
    }

    public static class Bucket {

        /** Requests allowed per window, per identity. */
        private int limit;

        /** Window length. Spring parses 1m, 10m, 30s and so on. */
        private Duration window;

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }
}
