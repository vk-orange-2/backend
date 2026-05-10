package ru.configplatform.configserver.exception;

import java.util.UUID;

public class RolloutNotFoundException extends RuntimeException {
    public RolloutNotFoundException(UUID id) {
        super("Rollout not found: " + id);
    }
}
