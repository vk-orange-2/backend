package ru.configplatform.configserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.configplatform.configserver.dto.ConfigStateResponse;
import ru.configplatform.configserver.dto.CreateServiceRequest;
import ru.configplatform.configserver.dto.ServiceResponse;
import ru.configplatform.configserver.service.ConfigService;

import java.util.List;

@RestController
@RequestMapping("/v1/services")
@RequiredArgsConstructor
@Tag(name = "Services", description = "Service registry and environment state")
public class ServiceController {

    private final ConfigService configService;

    @Operation(summary = "List all services")
    @GetMapping
    public ResponseEntity<List<ServiceResponse>> getServices() {
        return ResponseEntity.ok(configService.getServices());
    }

    @Operation(summary = "Create a new service")
    @PostMapping
    public ResponseEntity<ServiceResponse> createService(
            @Valid @RequestBody CreateServiceRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(configService.createService(request));
    }

    @Operation(
            summary = "Get full config state for service + environment",
            description = "Returns a list of all active configs for the given service+env, "
                    + "including for each config: the last globally applied version, "
                    + "active gradual rollout state (if any), and canary version (if any). "
                    + "Used by SDK on startup/reconnect and by admin UI for transparency."
    )
    @ApiResponse(responseCode = "200", description = "Config state (may have empty configs list)")
    @GetMapping("/{serviceName}/envs/{envCode}/state")
    public ResponseEntity<ConfigStateResponse> getServiceEnvState(
            @PathVariable @NotBlank String serviceName,
            @PathVariable @NotBlank String envCode
    ) {
        return ResponseEntity.ok(configService.getServiceEnvState(serviceName, envCode));
    }
}
