package com.example.dto;

import java.time.Instant;

/** placedAt serialises as an ISO-8601 UTC instant, e.g. 2026-10-01T19:33:39.094Z. */
public record OrderSummary(Long orderId,
                           Instant placedAt,
                           String status,
                           int totalQuantity,
                           int lineCount) {
}
