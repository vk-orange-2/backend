package ru.itmo.config_streamer.sdk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTO for messages received from Centrifugo WebSocket.
 * Supports multiple message types: update, gradual_deploy, canary_deploy, config_deleted
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CentrifugoMessage {
    public String type;
    public String key;
    public long version;
    public Object payload;
    
    // Config ID (present in all message types)
    public String configId;
    
    // Fields for gradual_deploy messages
    public Integer deployment;
    public Integer totalDeployments;
    
    // Fields for canary_deploy messages
    public Integer canaryPercentage;
    
    // Rollout ID (present in update, gradual_deploy, canary_deploy)
    public String rolloutId;
    
    // Timestamp
    public String timestamp;
}
