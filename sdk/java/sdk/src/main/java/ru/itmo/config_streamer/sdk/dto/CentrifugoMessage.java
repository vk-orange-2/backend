package ru.itmo.config_streamer.sdk.dto;

/**
 * DTO for messages received from Centrifugo WebSocket.
 */
public class CentrifugoMessage {
    public String type;
    public String key;
    public int version;
    public Object payload;
    public Integer deployments;
}
