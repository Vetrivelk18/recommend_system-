package com.example.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Quintiles of user_features.fraction_organic over all 206,209 users. Each value is
 * the median of its own 20% band, so any pick lands at the centre of a real segment
 * rather than on an invented number. The top band is 0.5408, not 0.9 - even the 95th
 * percentile only reaches 0.61.
 */
public enum OrganicPreference {

    VERYLOW ("verylow",  "Rarely or never",                0.0139f),
    LOW     ("low",      "Occasionally",                   0.1190f),
    MEDIUM  ("medium",   "About a quarter of my shopping", 0.2687f),
    HIGH    ("high",     "A lot of it",                    0.3913f),
    VERYHIGH("veryhigh", "Mostly organic",                 0.5408f);

    private final String key;
    private final String label;
    private final float fractionOrganic;

    OrganicPreference(String key, String label, float fractionOrganic) {
        this.key = key;
        this.label = label;
        this.fractionOrganic = fractionOrganic;
    }

    @JsonValue
    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    public float getFractionOrganic() {
        return fractionOrganic;
    }

    @JsonCreator
    public static OrganicPreference fromKey(String key) {
        for (OrganicPreference p : values()) {
            if (p.key.equalsIgnoreCase(key)) {
                return p;
            }
        }
        throw new IllegalArgumentException("unknown organic preference: " + key);
    }
}
