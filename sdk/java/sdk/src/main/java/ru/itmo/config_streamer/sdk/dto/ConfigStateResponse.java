package ru.itmo.config_streamer.sdk.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Full state of all configs for a service+environment.
 * Response from GET /v1/services/{name}/envs/{env}/state
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfigStateResponse {
    public String serviceName;
    public String environment;
    public List<ConfigStateEntry> configs;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConfigStateEntry {
        public String configId;
        public String configKey;
        public Boolean isSecret;
        
        // Latest version saved in DB (may not be rolled out yet)
        public Long latestVersion;
        public Object latestPayload;
        
        // Latest version rolled out to ALL instances (via completed instant or gradual)
        // Null if never rolled out globally
        public Long globalVersion;
        public Object globalPayload;
        
        // Active gradual rollout info, if any
        public GradualRolloutState gradualRollout;
        
        // Active canary state, if any
        public CanaryState canary;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GradualRolloutState {
        public String rolloutId;
        public Long targetVersion;
        public Object targetPayload;
        public Integer totalDeployments;
        public Integer currentDeployment;
        public Integer deploymentIntervalSeconds;
        public String status;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CanaryState {
        public String rolloutId;
        public Long canaryVersion;
        public Object canaryPayload;
        public Integer percentage;
        public String status;
    }
}
