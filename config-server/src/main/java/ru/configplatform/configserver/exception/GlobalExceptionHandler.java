package ru.configplatform.configserver.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<Map<String, Object>> handleVersionConflict(
            VersionConflictException ex) {

        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", Map.of(
                        "code", "VERSION_CONFLICT",
                        "message", ex.getMessage(),
                        "expectedVersion", ex.getExpectedVersion(),
                        "actualVersion", ex.getActualVersion()
                )
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.put(error.getField(), error.getDefaultMessage()));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", Map.of(
                        "code", "VALIDATION_ERROR",
                        "message", "Request payload is invalid",
                        "details", fieldErrors
                )
        ));
    }

    @ExceptionHandler(ConfigNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ConfigNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", Map.of(
                        "code", "NOT_FOUND",
                        "message", ex.getMessage()
                )
        ));
    }

    @ExceptionHandler(VersionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleVersionNotFound(VersionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", Map.of(
                        "code", "VERSION_NOT_FOUND",
                        "message", ex.getMessage()
                )
        ));
    }

    @ExceptionHandler(ServiceAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleServiceAlreadyExists(ServiceAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", Map.of(
                        "code", "SERVICE_ALREADY_EXISTS",
                        "message", ex.getMessage()
                )
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", Map.of(
                        "code", "BAD_REQUEST",
                        "message", ex.getMessage()
                )
        ));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(
            MissingServletRequestParameterException ex) {

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", Map.of(
                        "code", "MISSING_PARAMETER",
                        "message", ex.getMessage()
                )
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", Map.of(
                        "code", "INTERNAL_ERROR",
                        "message", "An unexpected error occurred"
                )
        ));
    }
}
