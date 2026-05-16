package ru.configplatform.configserver.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Optional parameters for rollout rollback")
public class RollbackRolloutRequest {

    @Schema(
            description = "Target version to rollback to. "
                    + "If not specified, rolls back to the rollout's baseline version. "
                    + "Only applicable to canary rollouts.",
            example = "3",
            nullable = true
    )
    private Long targetVersion;

    @Schema(description = "Optional comment for audit trail", nullable = true)
    private String comment;
}
