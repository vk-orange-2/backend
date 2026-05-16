package ru.configplatform.configserver.exception;

import java.util.UUID;

public class ActiveRolloutExistsException extends RuntimeException {
    private final UUID configId;
    private final UUID rolloutId;

    public ActiveRolloutExistsException(UUID configId, UUID rolloutId) {
        super("Config " + configId + " already has an active rollout: " + rolloutId);
        this.configId = configId;
        this.rolloutId = rolloutId;
    }

    public UUID getConfigId() {
        return configId;
    }

    public UUID getRolloutId() {
        return rolloutId;
    }
}
