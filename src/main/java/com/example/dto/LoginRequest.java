package com.example.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String mobile,
        @NotBlank String password) {
}
