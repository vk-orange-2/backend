package ru.configplatform.configserver.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum RolloutStatus {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    STOPPED("stopped"),
    ROLLED_BACK("rolled_back");

    private final String value;

    RolloutStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public boolean isActive() {
        return this == PENDING || this == IN_PROGRESS;
    }

    public static RolloutStatus fromValue(String value) {
        for (RolloutStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid rollout status: " + value);
    }
}
