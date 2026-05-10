package ru.configplatform.configserver.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.configplatform.configserver.dto.ConfigListResponse;
import ru.configplatform.configserver.dto.ConfigResponse;
import ru.configplatform.configserver.dto.CreateConfigRequest;
import ru.configplatform.configserver.dto.DeleteConfigRequest;
import ru.configplatform.configserver.dto.DiffResponse;
import ru.configplatform.configserver.dto.RequestContext;
import ru.configplatform.configserver.dto.RollbackRequest;
import ru.configplatform.configserver.dto.UpdateConfigRequest;
import ru.configplatform.configserver.dto.VersionHistoryResponse;
import ru.configplatform.configserver.dto.VersionResponse;
import ru.configplatform.configserver.service.ConfigService;

import java.util.UUID;

@RestController
@RequestMapping("/v1/configs")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigService configService;

    /**
     * POST /v1/configs — создать или обновить конфиг.
     */
    @PostMapping
    public ResponseEntity<ConfigResponse> createOrUpdate(
            @Valid @RequestBody  CreateConfigRequest request,
            HttpServletRequest httpRequest
    ) {
        RequestContext ctx = extractContext(httpRequest);
        ConfigResponse response = configService.createOrUpdate(request, ctx);
        boolean isNew = response.getCurrentVersion() == 1;

        return ResponseEntity
                .status(isNew ? HttpStatus.CREATED : HttpStatus.OK)
                .body(response);
    }

    /**
     * GET /v1/configs?serviceName=&environment= — список конфигов.
     */
    @GetMapping
    public ResponseEntity<ConfigListResponse> getConfigs(
            @RequestParam @NotBlank String serviceName,
            @RequestParam @NotBlank String environment
    ) {
        ConfigListResponse response = configService.getConfigs(serviceName, environment);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /v1/configs/{id} — получить конфиг по ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ConfigResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(configService.getById(id));
    }

    /**
     * PUT /v1/configs/{id} — обновить value конфига по ID.
     */
    @PutMapping("/{id}")
    public ResponseEntity<ConfigResponse> updateById(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateConfigRequest request,
            HttpServletRequest httpRequest
    ) {
        RequestContext ctx = extractContext(httpRequest);
        return ResponseEntity.ok(configService.updateById(id, request, ctx));
    }

    /**
     * DELETE /v1/configs/{id} — мягкое удаление.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteById(
            @PathVariable UUID id,
            @Valid @RequestBody DeleteConfigRequest request,
            HttpServletRequest httpRequest
    ) {
        RequestContext ctx = extractContext(httpRequest);
        configService.deleteById(id, request.getExpectedVersion(), ctx);
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /v1/configs/{id}/versions — история версий (FR-21).
     */
    @GetMapping("/{id}/versions")
    public ResponseEntity<VersionHistoryResponse> getVersionHistory(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(configService.getVersionHistory(id));
    }

    /**
     * GET /v1/configs/{id}/versions/{version} — конкретная версия (FR-22).
     */
    @GetMapping("/{id}/versions/{version}")
    public ResponseEntity<VersionResponse> getVersion(
            @PathVariable UUID id,
            @PathVariable long version
    ) {
        return ResponseEntity.ok(configService.getVersion(id, version));
    }

    /**
     * GET /v1/configs/{id}/diff?from=1&to=3 — diff между версиями (FR-25).
     */
    @GetMapping("/{id}/diff")
    public ResponseEntity<DiffResponse> getDiff(
            @PathVariable UUID id,
            @RequestParam long from,
            @RequestParam long to
    ) {
        return ResponseEntity.ok(configService.getDiff(id, from, to));
    }

    /**
     * POST /v1/configs/{id}/rollback — откат к указанной версии (FR-23).
     *
     * Создает НОВУЮ версию с payload из targetVersion (FR-24).
     */
    @PostMapping("/{id}/rollback")
    public ResponseEntity<ConfigResponse> rollback(
            @PathVariable UUID id,
            @RequestBody @Valid RollbackRequest request,
            HttpServletRequest httpRequest
    ) {
        RequestContext ctx = extractContext(httpRequest);
        return ResponseEntity.ok(configService.rollback(id, request, ctx));
    }

    private RequestContext extractContext(HttpServletRequest request) {
        String author = request.getHeader("X-Author");
        if (author == null || author.isBlank()) {
            author = "anonymous";
        }

        String sourceIp = request.getHeader("X-Forwarded-For");
        if (sourceIp == null) {
            sourceIp = request.getRemoteAddr();
        }

        return RequestContext.builder()
                .actor(author)
                .sourceIp(sourceIp)
                .userAgent(request.getHeader("User-Agent"))
                .build();
    }
}
