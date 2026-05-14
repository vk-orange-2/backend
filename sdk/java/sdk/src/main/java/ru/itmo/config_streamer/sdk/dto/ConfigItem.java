package ru.itmo.config_streamer.sdk.dto;

import java.sql.Date;

/**
 * DTO for a single config item in the config list response.
 */
public class ConfigItem {
    public String id;
    public String configKey;
    public String service;
    public String environment;
    public int currentVersion;
    public VersionInfo latestVersion;
    public boolean isSecret;
    public String status;
    public Date createdAt;
    public Date updatedAt;
}
