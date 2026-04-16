package ru.itmo.config_streamer.sdk;

public record Config(String key, int version, Object payload) implements Cloneable {
    @Override
    public Config clone() {
        return new Config(key, version, payload);
    }
}
