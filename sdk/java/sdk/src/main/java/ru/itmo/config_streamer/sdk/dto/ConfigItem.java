package ru.itmo.config_streamer.sdk.dto;

/**
 * DTO for a single config item in the config list response.
 */
public class ConfigItem {
    public String configKey;
    public int currentVersion;
    public VersionInfo latestVersion;
}
