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
public class AuditLogResponse {

    private UUID id;
    private UUID configId;
    private String serviceName;
    private String environment;
    private String configKey;
    private String operation;
    private String actor;
    private String sourceIp;
    private Long versionBefore;
    private Long versionAfter;
    private Object diff;
    private Instant createdAt;
}
