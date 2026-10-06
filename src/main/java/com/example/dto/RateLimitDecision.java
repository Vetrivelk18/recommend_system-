package com.example.dto;

/**
 * One rate-limit verdict.
 *
 * <p>retryAfterSeconds is the remaining life of the counter key, so it is only
 * meaningful when the request was rejected.
 */
public record RateLimitDecision(boolean allowed,
                                int limit,
                                long remaining,
                                long retryAfterSeconds) {

    /**
     * The verdict used when Redis could not answer. Limits are unknowable, so the
     * numbers are zero and only the allow matters - see RateLimitService for why this
     * is an allow rather than a deny.
     */
    public static RateLimitDecision unenforced() {
        return new RateLimitDecision(true, 0, 0, 0);
    }
}
