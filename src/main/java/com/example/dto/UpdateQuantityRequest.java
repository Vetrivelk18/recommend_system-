package com.example.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** An absolute quantity, not a delta. Zero is a remove, which is why Min is 0 and not 1. */
public record UpdateQuantityRequest(@NotNull @Min(0) @Max(99) Integer quantity) {
}
