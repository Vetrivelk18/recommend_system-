package com.example.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Four buckets rather than 24 hours. Besides being a better dropdown, buckets avoid
 * emitting hour 23 and hour 0 - one hour apart in reality, maximally distant once
 * scaled, because hour is treated as continuous rather than cyclical.
 */
public enum TimeOfDay {

    MORNING("Morning", 9),
    AFTERNOON("Afternoon", 14),
    EVENING("Evening", 19),
    NIGHT("Night", 22);

    private final String label;
    private final int hour;

    TimeOfDay(String label, int hour) {
        this.label = label;
        this.hour = hour;
    }

    @JsonValue
    public String getLabel() {
        return label;
    }

    public int getHour() {
        return hour;
    }

    /** Hardcoded 23.0, matching training. */
    public float normalised() {
        return hour / 23.0f;
    }

    @JsonCreator
    public static TimeOfDay fromLabel(String label) {
        for (TimeOfDay t : values()) {
            if (t.label.equalsIgnoreCase(label)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown time of day: " + label);
    }
}
