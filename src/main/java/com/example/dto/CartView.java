package com.example.dto;

import java.util.List;

/**
 * The whole cart. Every mutating endpoint returns this rather than a bare ack, so the
 * client never has to follow a write with a read to refresh its badge.
 *
 * <p>lineCount is distinct products, totalQuantity is units - the badge wants the first,
 * the checkout summary wants the second.
 */
public record CartView(Integer userId, List<CartLine> lines, int lineCount, int totalQuantity) {

    public static CartView of(Integer userId, List<CartLine> lines) {
        return new CartView(userId, lines, lines.size(),
                lines.stream().mapToInt(CartLine::quantity).sum());
    }
}
