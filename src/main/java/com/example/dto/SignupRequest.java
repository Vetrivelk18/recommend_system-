package com.example.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** preferences may be null if the user skips the survey. */
public record SignupRequest(
        @NotBlank String name,
        @NotBlank @Pattern(regexp = "\\d{10,15}", message = "mobile must be 10-15 digits") String mobile,
        @NotBlank @Size(min = 8, message = "password must be at least 8 characters") String password,
        @Valid OnboardingPreferences preferences) {
}
