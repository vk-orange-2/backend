package ru.configplatform.configserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfigResponse {

    private UUID id;
    private String service;
    private String env;
    private String key;
    private String value;
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;
}
