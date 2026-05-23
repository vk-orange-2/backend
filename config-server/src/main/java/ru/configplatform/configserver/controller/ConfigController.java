package ru.configplatform.configserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.configplatform.configserver.dto.*;
import ru.configplatform.configserver.service.ConfigService;
import ru.configplatform.configserver.service.RolloutService;

import java.util.UUID;

@RestController
@RequestMapping("/v1/configs")
@RequiredArgsConstructor
@Tag(name = "Configs", description = "Configuration management. Note: saving config does NOT deliver it to clients. Use Rollout API for delivery operation.")
public class ConfigController {

    private final ConfigService configService;
    private final RolloutService rolloutService;

    @Operation(summary = "Create or update config",
            description = "Creates new config or updates existing (upsert by service+env+key). "
                    + "Does NOT publish to clients — use Rollout API for delivery this operation.")
    @ApiResponse(responseCode = "201", description = "Config created (version 1)")
    @ApiResponse(responseCode = "200", description = "Config updated (version incremented)")
    @ApiResponse(responseCode = "409", description = "Version conflict or active rollout exists")
    @PostMapping
    public ResponseEntity<ConfigResponse> createOrUpdate(
            @Valid @RequestBody CreateConfigRequest request,
            HttpServletRequest httpRequest
    ) {
        RequestContext ctx = extractContext(httpRequest);
        ConfigResponse response = configService.createOrUpdate(request, ctx);
        boolean isNew = response.getCurrentVersion() == 1;

        return ResponseEntity
                .status(isNew ? HttpStatus.CREATED : HttpStatus.OK)
                .body(response);
    }

    @Operation(summary = "List configs by service and environment")
    @GetMapping
    public ResponseEntity<ConfigListResponse> getConfigs(
            @RequestParam @NotBlank String serviceName,
            @RequestParam @NotBlank String environment
    ) {
        ConfigListResponse response = configService.getConfigs(serviceName, environment);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get config by ID")
    @ApiResponse(responseCode = "404", description = "Config not found or deleted")
    @GetMapping("/{id}")
    public ResponseEntity<ConfigResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(configService.getById(id));
    }

    @Operation(summary = "Update config value by ID",
            description = "Updates config value. Does NOT publish to clients — use Rollout API after updating for this.")
    @ApiResponse(responseCode = "409", description = "Version conflict or active rollout exists")
    @PutMapping("/{id}")
    public ResponseEntity<ConfigResponse> updateById(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateConfigRequest request,
            HttpServletRequest httpRequest
    ) {
        RequestContext ctx = extractContext(httpRequest);
        return ResponseEntity.ok(configService.updateById(id, request, ctx));
    }

    @Operation(summary = "Delete config by ID (soft delete). Also publishes event to centrifugo")
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

    @Operation(summary = "Get version history")
    @GetMapping("/{id}/versions")
    public ResponseEntity<VersionHistoryResponse> getVersionHistory(@PathVariable UUID id) {
        return ResponseEntity.ok(configService.getVersionHistory(id));
    }

    @Operation(summary = "Get specific version")
    @ApiResponse(responseCode = "404", description = "Version not found")
    @GetMapping("/{id}/versions/{version}")
    public ResponseEntity<VersionResponse> getVersion(
            @PathVariable UUID id,
            @PathVariable long version
    ) {
        return ResponseEntity.ok(configService.getVersion(id, version));
    }

    @Operation(summary = "Get diff between two versions")
    @GetMapping("/{id}/diff")
    public ResponseEntity<DiffResponse> getDiff(
            @PathVariable UUID id,
            @RequestParam long from,
            @RequestParam long to
    ) {
        return ResponseEntity.ok(configService.getDiff(id, from, to));
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
