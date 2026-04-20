package ru.configplatform.configserver.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeleteConfigRequest {

    @NotNull(message = "expectedVersion is required for deletion")
    @Min(value = 1, message = "expectedVersion must be >= 1")
    private Long expectedVersion;
}