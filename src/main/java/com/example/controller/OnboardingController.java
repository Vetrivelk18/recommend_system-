package com.example.controller;

import com.example.dto.DayPreference;
import com.example.dto.OnboardingOptions;
import com.example.dto.OnboardingOptions.Option;
import com.example.dto.OnboardingOptions.OrganicOption;
import com.example.dto.OrganicPreference;
import com.example.dto.TimeOfDay;
import com.example.repository.AisleRepository;
import com.example.repository.DepartmentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * One endpoint per dropdown, plus /options which returns all five at once for a
 * client that would rather make a single call on page load.
 */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

    private final DepartmentRepository departmentRepository;
    private final AisleRepository aisleRepository;

    public OnboardingController(DepartmentRepository departmentRepository,
                                AisleRepository aisleRepository) {
        this.departmentRepository = departmentRepository;
        this.aisleRepository = aisleRepository;
    }

    @GetMapping("/departments")
    public List<Option> departments() {
        return departmentRepository.findAllByOrderByNameAsc().stream()
                .map(d -> new Option(d.getDepartmentId(), d.getName()))
                .toList();
    }

    @GetMapping("/aisles")
    public List<Option> aisles() {
        return aisleRepository.findAllByOrderByNameAsc().stream()
                .map(a -> new Option(a.getAisleId(), a.getName()))
                .toList();
    }

    @GetMapping("/days")
    public List<String> days() {
        return Arrays.stream(DayPreference.values()).map(DayPreference::getLabel).toList();
    }

    @GetMapping("/times")
    public List<String> times() {
        return Arrays.stream(TimeOfDay.values()).map(TimeOfDay::getLabel).toList();
    }

    @GetMapping("/organic")
    public List<OrganicOption> organic() {
        return Arrays.stream(OrganicPreference.values())
                .map(o -> new OrganicOption(o.getKey(), o.getLabel()))
                .toList();
    }

    @GetMapping("/options")
    public OnboardingOptions options() {
        return new OnboardingOptions(departments(), aisles(), days(), times(), organic());
    }
}
