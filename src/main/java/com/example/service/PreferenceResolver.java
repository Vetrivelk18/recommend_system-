package com.example.service;

import com.example.dto.OnboardingPreferences;
import com.example.repository.AisleRepository;
import com.example.repository.DepartmentRepository;
import org.springframework.stereotype.Service;

/**
 * Turns the survey labels into the ids the model needs, on the write path, so a bad
 * label fails at signup where the cause is obvious.
 *
 * Aisle and department are resolved independently and deliberately not cross-checked:
 * most_common_aisle and most_common_department are independent modes, so 44,617 of the
 * 206,209 real users (21.6%) have a modal aisle outside their modal department.
 */
@Service
public class PreferenceResolver {

    private final DepartmentRepository departmentRepository;
    private final AisleRepository aisleRepository;

    public PreferenceResolver(DepartmentRepository departmentRepository,
                              AisleRepository aisleRepository) {
        this.departmentRepository = departmentRepository;
        this.aisleRepository = aisleRepository;
    }

    public Resolved resolve(OnboardingPreferences prefs) {
        Integer departmentId = departmentRepository.findIdByName(prefs.department());
        if (departmentId == null) {
            throw new UnknownPreferenceException("department", prefs.department());
        }
        Integer aisleId = aisleRepository.findIdByName(prefs.aisle());
        if (aisleId == null) {
            throw new UnknownPreferenceException("aisle", prefs.aisle());
        }
        return new Resolved(departmentId, aisleId);
    }

    public record Resolved(Integer departmentId, Integer aisleId) {
    }

    public static class UnknownPreferenceException extends RuntimeException {
        public UnknownPreferenceException(String field, String value) {
            super("unknown " + field + ": " + value);
        }
    }
}
