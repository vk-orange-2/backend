package ru.itmo.config_streamer.sdk.dto;

import java.util.UUID;

/**
 * DTO for rollout response from backend.
 */
public class RolloutResponse {
    public UUID id;
    public UUID configId;
    public String type;
    public String status;
    public Long baselineVersion;
    public Long targetVersion;
    public Integer totalDeployments;
    public Integer currentDeployment;
    public Integer deploymentIntervalSeconds;
    public String nextDeploymentAt;
    public String createdAt;
    public String startedAt;
    public String completedAt;
    public String stoppedAt;
    public String rolledBackAt;
}
