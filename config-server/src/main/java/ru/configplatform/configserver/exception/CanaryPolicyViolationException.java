package ru.configplatform.configserver.exception;

public class CanaryPolicyViolationException extends RuntimeException {

    private final String reason;

    public CanaryPolicyViolationException(String reason) {
        super("Canary policy violation: " + reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
