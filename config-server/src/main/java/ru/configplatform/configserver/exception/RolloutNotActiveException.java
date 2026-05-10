package ru.configplatform.configserver.exception;

import java.util.UUID;

public class RolloutNotActiveException extends RuntimeException {
    public RolloutNotActiveException(UUID rolloutId, String currentStatus) {
        super("Rollout " + rolloutId + " is not active (current status: " + currentStatus + ")");
    }
}
