package com.example.dto;

import java.util.List;

/**
 * The full catalogue of survey choices. Returned before the user has picked anything,
 * so every option is present - this is what populates the empty dropdowns.
 * Aisle and department are chosen independently.
 */
public record OnboardingOptions(
        List<Option> departments,
        List<Option> aisles,
        List<String> days,
        List<String> times,
        List<OrganicOption> organic) {

    public record Option(Integer id, String name) {
    }

    public record OrganicOption(String value, String label) {
    }
}
