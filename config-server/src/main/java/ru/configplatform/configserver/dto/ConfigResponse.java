package ru.configplatform.configserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfigResponse {

    private String configKey;
    private int currentVersion;
    private LatestVersion latestVersion;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LatestVersion {
        private Object payload;
    }
}
