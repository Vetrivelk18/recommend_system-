package com.example.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The survey answers exactly as the user picked them. Labels are stored rather than
 * ids so the row stays readable; they resolve to ids on the way in. Department and
 * aisle strings originate from GET /api/onboarding/options, which reads the same
 * tables, so they round-trip byte-exact.
 */
public record OnboardingPreferences(
        @JsonProperty("DEPARTMENT") @NotBlank String department,
        @JsonProperty("AISLE") @NotBlank String aisle,
        @JsonProperty("DOW") @NotNull DayPreference dow,
        @JsonProperty("HOUR") @NotNull TimeOfDay hour,
        @JsonProperty("ORGANIC") @NotNull OrganicPreference organic) {
}
