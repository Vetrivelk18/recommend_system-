package com.example.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** quantity is a delta, defaulting to one tap of Add. */
public record AddToCartRequest(@NotNull Integer productId,
                               @Min(1) @Max(99) Integer quantity) {

    public int quantityOrOne() {
        return quantity == null ? 1 : quantity;
    }
}
