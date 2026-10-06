package com.example.dto;

/** One cart row. inStock is live from products, not stored on the cart. */
public record CartLine(Integer productId, String product, int quantity, boolean inStock) {
}
