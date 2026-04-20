package ru.configplatform.configserver.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConfigResponse {

    private UUID id;
    private String configKey;
    private String service;
    private String environment;
    private Boolean isSecret;
    private String status;
    private int currentVersion;
    private LatestVersion latestVersion;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LatestVersion {
        private Object payload;
    }
}
