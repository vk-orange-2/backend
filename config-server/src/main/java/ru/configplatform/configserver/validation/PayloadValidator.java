package ru.configplatform.configserver.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class PayloadValidator {

    private final ObjectMapper jsonMapper = new ObjectMapper();

    public void validate(Object payload, String format) {
        switch (format) {
            case "json" -> validateJson(payload);
            default -> throw new IllegalArgumentException("Unknown format: " + format);
        }
    }

    private void validateJson(Object payload) {
        try {
            String json = jsonMapper.writeValueAsString(payload);
            jsonMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON payload: " + e.getMessage());
        }
    }
}
