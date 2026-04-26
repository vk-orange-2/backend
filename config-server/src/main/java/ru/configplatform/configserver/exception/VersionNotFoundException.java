package ru.configplatform.configserver.exception;

import java.util.UUID;

public class VersionNotFoundException extends RuntimeException {
    public VersionNotFoundException(UUID configId, long version) {
        super("Version " + version + " not found for config " + configId);
    }
}
