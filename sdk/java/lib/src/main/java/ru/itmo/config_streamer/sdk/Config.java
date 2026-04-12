package ru.itmo.config_streamer.sdk;

public record Config(String key, int version, byte[] payload) {
}
