package ru.configplatform.configserver.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Rollout details")
public class RolloutResponse {
    private UUID id;
    private UUID configId;

    @Schema(description = "Rollout type", example = "gradual")
    private String type;

    @Schema(description = "Rollout status", example = "in_progress")
    private String status;

    @Schema(description = "Config version before rollout (rollback target)")
    private Long baselineVersion;

    @Schema(description = "Config version being rolled out")
    private Long targetVersion;

    @Schema(description = "Total number of deployment stages")
    private Integer totalDeployments;

    @Schema(description = "Current completed deployment number")
    private Integer currentDeployment;

    @Schema(description = "Seconds between deployments")
    private Integer deploymentIntervalSeconds;

    @Schema(description = "Canary percentage (only for canary rollouts)")
    private Integer canaryPercentage;

    private Instant nextDeploymentAt;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private Instant stoppedAt;
    private Instant rolledBackAt;
}
