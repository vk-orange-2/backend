package ru.configplatform.configserver.exception;

public class VersionConflictException extends RuntimeException {

    private final long expectedVersion;
    private final long actualVersion;

    public VersionConflictException(long expectedVersion, long actualVersion) {
        super(String.format(
                "Version conflict: expected %d, but current version is %d. Someone else has modified this config.",
                expectedVersion, actualVersion
        ));
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public long getActualVersion() {
        return actualVersion;
    }
}
