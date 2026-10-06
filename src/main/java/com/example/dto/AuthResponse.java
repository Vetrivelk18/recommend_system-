package com.example.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Never carries the password hash. preferences echoes what was persisted, so the
 * client can confirm the survey landed; it is null when the survey was skipped.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthResponse(
        String token,
        Integer userId,
        String name,
        OnboardingPreferences preferences) {
}
