package ru.configplatform.configserver.exception;

import java.util.UUID;

public class ConfigNotFoundException extends RuntimeException {
    public ConfigNotFoundException(UUID id) {
        super("Config not found: " + id);
    }
}

