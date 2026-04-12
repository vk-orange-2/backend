package ru.configplatform.configserver.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.configplatform.configserver.dto.ConfigResponse;
import ru.configplatform.configserver.dto.CreateConfigRequest;
import ru.configplatform.configserver.service.ConfigService;

import java.util.List;

@RestController
@RequestMapping("/v1/configs")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigService configService;

    /**
     * POST /v1/configs — создать или обновить конфигурацию
     *
     * Если конфиг с таким (service, env, key) уже существует — обновляет value и инкрементирует version
     * Иначе создает новый с version=1
     */
    @PostMapping
    public ResponseEntity<ConfigResponse> createOrUpdate(
            @Valid @RequestBody CreateConfigRequest request) {

        ConfigResponse response = configService.createOrUpdate(request);
        boolean isNewConfig = response.getVersion() == 1L;

        return ResponseEntity
                .status(isNewConfig ? HttpStatus.CREATED : HttpStatus.OK)
                .body(response);
    }

    /**
     * GET /v1/configs?serviceName=my-service&environment=prod
     *
     * Query-параметры serviceName и environment — оба обязательны
     * Возвращает список конфигов данного сервиса в данном окружении
     */
    @GetMapping
    public ResponseEntity<List<ConfigResponse>> getConfigs(
            @RequestParam @NotBlank String serviceName,
            @RequestParam @NotBlank String environment) {

        List<ConfigResponse> configs = configService.getConfigs(serviceName, environment);
        return ResponseEntity.ok(configs);
    }
}
