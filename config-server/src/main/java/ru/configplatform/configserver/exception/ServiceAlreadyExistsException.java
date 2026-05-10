package ru.configplatform.configserver.exception;

public class ServiceAlreadyExistsException extends RuntimeException {
    public ServiceAlreadyExistsException(String name) {
        super("Service already exists: " + name);
    }
}
