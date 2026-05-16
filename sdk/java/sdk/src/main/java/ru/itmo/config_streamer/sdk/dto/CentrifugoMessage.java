package ru.itmo.config_streamer.sdk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTO for messages received from Centrifugo WebSocket.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CentrifugoMessage {
    public String type;
    public String key;
    public int version;
    public Object payload;
    
    // Fields for gradual_deploy messages
    public String configId;
    public Integer deployment;
    public Integer totalDeployments;
    public String timestamp;
}
