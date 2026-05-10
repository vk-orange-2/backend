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
public class VersionResponse {

    private UUID id;
    private UUID configId;
    private long version;
    private Object payload;
    private String changeType;
    private String author;
    private String comment;
    private Instant createdAt;
}
