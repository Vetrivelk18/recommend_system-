package com.example.dto;

/** product is the name as it was at checkout, not as the catalogue reads now. */
public record OrderLine(Integer productId, String product, int quantity) {
}
