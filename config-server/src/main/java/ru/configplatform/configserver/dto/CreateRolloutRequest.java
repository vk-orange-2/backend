package ru.configplatform.configserver.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to create and start a rollout")
public class CreateRolloutRequest {

    @NotNull(message = "configId is required")
    @Schema(description = "ID of the config to rollout")
    private UUID configId;

    /**
     * Тип rollout: "instant" или "gradual"
     */
    @NotNull(message = "type is required")
    @Schema(description = "Rollout type", example = "instant", allowableValues = {"instant", "gradual"})
    private String type;

    /**
     * Количество deployment-ов для gradual rollout.
     * Для instant игнорируется (используется 1).
     */
    @Min(value = 1, message = "totalDeployments must be >= 1")
    @Max(value = 100, message = "totalDeployments must be <= 100")
    @Builder.Default
    @Schema(description = "Number of deployments for gradual rollout", example = "4")
    private Integer totalDeployments = 1;

    /**
     * Интервал между deployment-ами в секундах (для gradual).
     */
    @Min(value = 0, message = "deploymentIntervalSeconds must be >= 0")
    @Builder.Default
    @Schema(description = "Interval between deployments in seconds", example = "60")
    private Integer deploymentIntervalSeconds = 60;
}
