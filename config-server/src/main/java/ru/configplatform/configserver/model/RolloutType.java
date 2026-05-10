package ru.configplatform.configserver.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum RolloutType {
    INSTANT("instant"),
    GRADUAL("gradual");

    private final String value;

    RolloutType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static RolloutType fromValue(String value) {
        for (RolloutType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                "Invalid rollout type: " + value + ". Valid values: instant, gradual");
    }
}
