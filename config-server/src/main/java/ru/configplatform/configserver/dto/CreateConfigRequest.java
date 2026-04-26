package ru.configplatform.configserver.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateConfigRequest {

    @NotBlank(message = "service is required")
    @Size(max = 255)
    private String service;

    @NotBlank(message = "env is required")
    @Size(max = 50)
    private String env;

    @NotBlank(message = "key is required")
    @Size(max = 255)
    private String key;

    @NotNull(message = "value is required")
    private Object value;

    @Builder.Default
    private Boolean isSecret = false;

    @Min(value = 0, message = "expectedVersion must be >= 0")
    private Long expectedVersion;
}
