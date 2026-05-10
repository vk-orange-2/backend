package ru.configplatform.configserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Контекст HTTP-запроса, извлекаемый контроллером
 *
 * Содержит метаданные для аудита (FR-58, FR-59, FR-60):
 * - actor: из заголовка X-Author (позднее — из JWT-токена)
 * - sourceIp: из заголовка X-Forwarded-For или remoteAddr
 * - userAgent: из заголовка User-Agent
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestContext {

    @Builder.Default
    private String actor = "anonymous";

    private String sourceIp;
    private String userAgent;
}
