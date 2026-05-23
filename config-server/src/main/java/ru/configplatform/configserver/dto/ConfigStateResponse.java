package ru.configplatform.configserver.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Full state of all configs for a service+environment")
public class ConfigStateResponse {

    @Schema(description = "Service name")
    private String serviceName;

    @Schema(description = "Environment code")
    private String environment;

    @Schema(description = "State of each config in this service+env")
    private List<ConfigStateEntry> configs;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "State of a single config")
    public static class ConfigStateEntry {

        @Schema(description = "Config ID")
        private UUID configId;

        @Schema(description = "Config key")
        private String configKey;

        @Schema(description = "Whether config is secret")
        private Boolean isSecret;

        @Schema(description = "Latest version saved in DB (may not be rolled out yet)")
        private Long latestVersion;
        @Schema(description = "Payload of the latest version")
        private Object latestPayload;

        @Schema(description = "Latest version rolled out to ALL instances "
                + "(via completed instant or gradual). Null if never rolled out globally.")
        private Long globalVersion;

        @Schema(description = "Payload of the global version")
        private Object globalPayload;

        @Schema(description = "Active gradual rollout info, if any")
        private GradualRolloutState gradualRollout;

        @Schema(description = "Active canary state, if any")
        private CanaryState canary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Gradual rollout state")
    public static class GradualRolloutState {

        private UUID rolloutId;
        private Long targetVersion;
        private Object targetPayload;
        private Integer totalDeployments;
        private Integer currentDeployment;
        private Integer deploymentIntervalSeconds;
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Canary state")
    public static class CanaryState {

        private UUID rolloutId;
        private Long canaryVersion;
        private Object canaryPayload;
        private Integer percentage;
        private String status;
    }
}
