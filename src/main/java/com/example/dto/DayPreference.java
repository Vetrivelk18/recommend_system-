package com.example.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Instacart order_dow: 0 = Sunday. */
public enum DayPreference {

    SUNDAY("Sunday", 0),
    MONDAY("Monday", 1),
    TUESDAY("Tuesday", 2),
    WEDNESDAY("Wednesday", 3),
    THURSDAY("Thursday", 4),
    FRIDAY("Friday", 5),
    SATURDAY("Saturday", 6);

    private final String label;
    private final int dow;

    DayPreference(String label, int dow) {
        this.label = label;
        this.dow = dow;
    }

    @JsonValue
    public String getLabel() {
        return label;
    }

    public int getDow() {
        return dow;
    }

    /** Hardcoded 6.0, matching training, so the serving path cannot drift. */
    public float normalised() {
        return dow / 6.0f;
    }

    @JsonCreator
    public static DayPreference fromLabel(String label) {
        for (DayPreference d : values()) {
            if (d.label.equalsIgnoreCase(label)) {
                return d;
            }
        }
        throw new IllegalArgumentException("unknown day: " + label);
    }
}
