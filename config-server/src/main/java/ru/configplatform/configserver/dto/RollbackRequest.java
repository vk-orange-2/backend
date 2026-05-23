package ru.configplatform.configserver.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Request to rollback a config to a specific version")
public class RollbackRequest {

    @NotNull(message = "targetVersion is required")
    @Min(value = 1, message = "targetVersion must be >= 1")
    @Schema(description = "Version to rollback to", example = "3")
    private Long targetVersion;

    @NotNull(message = "expectedVersion is required")
    @Schema(description = "Expected current version (optimistic lock)", example = "5")
    @Min(value = 1, message = "expectedVersion must be >= 1")
    private Long expectedVersion;

    @Schema(description = "Optional comment for audit trail", nullable = true)
    private String comment;
}
