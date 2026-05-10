package ru.configplatform.configserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.configplatform.configserver.dto.CreateRolloutRequest;
import ru.configplatform.configserver.dto.RequestContext;
import ru.configplatform.configserver.dto.RolloutResponse;
import ru.configplatform.configserver.service.RolloutService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/rollouts")
@RequiredArgsConstructor
@Tag(name = "Rollouts", description = "Configuration delivery management. Rollouts are the ONLY way to deliver config changes to clients.")
public class RolloutController {

    private final RolloutService rolloutService;

    @Operation(summary = "Create and start rollout",
            description = "Creates a rollout for the current version of the config. "
                    + "Instant: immediately delivers to all clients. "
                    + "Gradual: delivers in batches over time.")
    @ApiResponse(responseCode = "201", description = "Rollout created and started")
    @ApiResponse(responseCode = "409", description = "Active rollout already exists for this config")
    @PostMapping
    public ResponseEntity<RolloutResponse> create(
            @Valid @RequestBody CreateRolloutRequest request,
            HttpServletRequest httpRequest
    ) {
        RequestContext ctx = extractContext(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(rolloutService.createAndStart(request, ctx));
    }

    @Operation(summary = "Get rollout by ID")
    @ApiResponse(responseCode = "404", description = "Rollout not found")
    @GetMapping("/{id}")
    public ResponseEntity<RolloutResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(rolloutService.getById(id));
    }

    @Operation(summary = "List rollouts for config")
    @GetMapping
    public ResponseEntity<List<RolloutResponse>> list(
            @RequestParam(required = false) UUID configId
    ) {
        if (configId != null) {
            return ResponseEntity.ok(rolloutService.getByConfigId(configId));
        }
        return ResponseEntity.ok(List.of());
    }

    @Operation(
            summary = "Stop rollout",
            description = "Stops further deployment propagation. "
                    + "Clients that already received the update keep it. "
                    + "Global config version is NOT changed."
    )
    @ApiResponse(responseCode = "409", description = "Rollout is not active")
    @PostMapping("/{id}/stop")
    public ResponseEntity<RolloutResponse> stop(
            @PathVariable UUID id,
            HttpServletRequest httpRequest
    ) {
        RequestContext ctx = extractContext(httpRequest);
        return ResponseEntity.ok(rolloutService.stop(id, ctx));
    }

    @Operation(
            summary = "Rollback rollout",
            description = "Rolls back the config to the baseline version of this rollout. "
                    + "Creates a new config version and publishes it to ALL clients immediately."
    )
    @ApiResponse(responseCode = "409", description = "Rollout is not active")
    @PostMapping("/{id}/rollback")
    public ResponseEntity<RolloutResponse> rollback(
            @PathVariable UUID id,
            HttpServletRequest httpRequest
    ) {
        RequestContext ctx = extractContext(httpRequest);
        return ResponseEntity.ok(rolloutService.rollback(id, ctx));
    }

    @Operation(summary = "Deploy next batch (for gradual rollout)",
            description = "Manually triggers the next deployment stage. "
                    + "Can also be triggered automatically by the scheduler.")
    @ApiResponse(responseCode = "409", description = "Rollout is not in_progress or all deployments done")
    @PostMapping("/{id}/deploy-next")
    public ResponseEntity<RolloutResponse> deployNext(
            @PathVariable UUID id,
            HttpServletRequest httpRequest
    ) {
        RequestContext ctx = extractContext(httpRequest);
        return ResponseEntity.ok(rolloutService.deployNext(id, ctx));
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
