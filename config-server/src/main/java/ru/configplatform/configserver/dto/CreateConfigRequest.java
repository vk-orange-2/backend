package ru.configplatform.configserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateConfigRequest {

    @NotBlank(message = "service is required")
    @Size(max = 255, message = "service must be at most 255 characters")
    private String service;

    @NotBlank(message = "env is required")
    @Size(max = 50, message = "env must be at most 50 characters")
    private String env;

    @NotBlank(message = "key is required")
    @Size(max = 255, message = "key must be at most 255 characters")
    private String key;

    @NotNull(message = "value is required")
    private Object value;
}
