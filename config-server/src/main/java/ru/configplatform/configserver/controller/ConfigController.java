package ru.configplatform.configserver.controller;

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
import ru.configplatform.configserver.dto.*;
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
    public ResponseEntity<ConfigResponse> createOrUpdate(@Valid @RequestBody CreateConfigRequest request) {
        ConfigResponse response = configService.createOrUpdate(request);
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
            @Valid @RequestBody UpdateConfigRequest request
    ) {
        return ResponseEntity.ok(configService.updateById(id, request));
    }

    /**
     * DELETE /v1/configs/{id} — мягкое удаление.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteById(
            @PathVariable UUID id,
            @Valid @RequestBody DeleteConfigRequest request
    ) {
        configService.deleteById(id, request.getExpectedVersion());
        return ResponseEntity.noContent().build();
    }
}
