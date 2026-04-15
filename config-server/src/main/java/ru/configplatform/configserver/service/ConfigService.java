package ru.configplatform.configserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.configplatform.configserver.dto.ConfigListResponse;
import ru.configplatform.configserver.dto.ConfigResponse;
import ru.configplatform.configserver.dto.CreateConfigRequest;
import ru.configplatform.configserver.dto.ServiceResponse;
import ru.configplatform.configserver.model.CentrifugoOutboxEntity;
import ru.configplatform.configserver.model.ConfigEntity;
import ru.configplatform.configserver.model.ConfigVersionEntity;
import ru.configplatform.configserver.model.EnvironmentEntity;
import ru.configplatform.configserver.model.ServiceEntity;
import ru.configplatform.configserver.repository.CentrifugoOutboxRepository;
import ru.configplatform.configserver.repository.ConfigRepository;
import ru.configplatform.configserver.repository.ConfigVersionRepository;
import ru.configplatform.configserver.repository.EnvironmentRepository;
import ru.configplatform.configserver.repository.ServiceRepository;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ConfigService {

    private final ServiceRepository serviceRepository;
    private final EnvironmentRepository environmentRepository;
    private final ConfigRepository configRepository;
    private final ConfigVersionRepository configVersionRepository;
    private final CentrifugoOutboxRepository centrifugoOutboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Создает или обновляет конфиг.
     *
     * В одной транзакции:
     * 1. Находит или создает сервис по имени
     * 2. Находит окружение по коду
     * 3. Находит или создает конфиг по (service, env, key)
     * 4. Инкрементирует версию
     * 5. Создает запись в config_versions (иммутабельная история)
     * 6. Пишет в centrifugo_outbox → триггер делает pg_notify → Centrifugo пушит клиентам
     */
    @Transactional
    public ConfigResponse createOrUpdate(CreateConfigRequest request) {
        // 1. Service — find or create
        ServiceEntity service = serviceRepository.findByName(request.getService())
                .orElseGet(() -> serviceRepository.save(
                        ServiceEntity.builder()
                                .name(request.getService())
                                .build()
                ));

        // 2. Environment — must exist
        EnvironmentEntity environment = environmentRepository.findByCode(request.getEnv())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown environment: " + request.getEnv() + ". Valid values: dev, stage, prod"
                ));

        // 3. Config — find or create
        String payloadJson = serializePayload(request.getValue());

        ConfigEntity config = configRepository
                .findByServiceAndEnvironmentAndConfigKey(service, environment, request.getKey())
                .orElseGet(() ->
                        ConfigEntity.builder()
                                .service(service)
                                .environment(environment)
                                .configKey(request.getKey())
                                .currentVersion(0L)
                                .build()
                );

        // 4. Increment version
        long newVersion = config.getCurrentVersion() + 1;
        config.setCurrentVersion(newVersion);
        config = configRepository.save(config);

        // 5. Create immutable version record
        String changeType = newVersion == 1 ? "create" : "update";
        ConfigVersionEntity version = ConfigVersionEntity.builder()
                .config(config)
                .version(newVersion)
                .payload(payloadJson)
                .changeType(changeType)
                .build();
        configVersionRepository.save(version);

        // 6. Write to centrifugo outbox → trigger fires pg_notify
        publishToCentrifugoOutbox(
                service.getName(),
                environment.getCode(),
                request.getKey(),
                newVersion,
                request.getValue()
        );

        return toResponse(config, request.getValue());
    }

    /**
     * Получить конфиги по имени сервиса и окружению.
     * Формат ответа совместим с SDK коллеги.
     */
    @Transactional(readOnly = true)
    public ConfigListResponse getConfigs(String serviceName, String envCode) {
        ServiceEntity service = serviceRepository.findByName(serviceName).orElse(null);

        if (service == null) {
            return ConfigListResponse.builder()
                    .configs(List.of())
                    .build();
        }

        EnvironmentEntity environment = environmentRepository.findByCode(envCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown environment: " + envCode));

        List<ConfigEntity> configs = configRepository
                .findByServiceAndEnvironment(service, environment);

        List<ConfigResponse> responses = configs.stream()
                .map(config -> {
                    // Загружаем последнюю версию
                    ConfigVersionEntity latestVersion = configVersionRepository
                            .findByConfigIdAndVersion(config.getId(), config.getCurrentVersion())
                            .orElse(null);

                    Object payload = null;
                    if (latestVersion != null) {
                        payload = deserializePayload(latestVersion.getPayload());
                    }

                    return toResponse(config, payload);
                })
                .toList();

        return ConfigListResponse.builder()
                .configs(responses)
                .build();
    }

    /**
     * Список всех сервисов
     */
    @Transactional(readOnly = true)
    public List<ServiceResponse> getServices() {
        return serviceRepository.findAll().stream()
                .map(s -> ServiceResponse.builder()
                        .id(s.getId())
                        .name(s.getName())
                        .description(s.getDescription())
                        .createdAt(s.getCreatedAt())
                        .build())
                .toList();
    }

    // ---- private helpers ----
    private void publishToCentrifugoOutbox(
            String serviceName,
            String envCode,
            String key,
            long version,
            Object payload
    ) {
        String channel = "service:" + serviceName + ":" + envCode;

        Map<String, Object> data = Map.of(
                "key", key,
                "version", version,
                "payload", payload
        );

        Map<String, Object> centrifugoPayload = Map.of(
                "channel", channel,
                "data", data
        );

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(centrifugoPayload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize centrifugo payload", e);
        }

        CentrifugoOutboxEntity outbox = CentrifugoOutboxEntity.builder()
                .method("publish")
                .payload(payloadJson)
                .partition(0)
                .build();

        centrifugoOutboxRepository.save(outbox);
    }

    private ConfigResponse toResponse(ConfigEntity config, Object payload) {
        return ConfigResponse.builder()
                .configKey(config.getConfigKey())
                .currentVersion(config.getCurrentVersion().intValue())
                .latestVersion(
                        ConfigResponse.LatestVersion.builder()
                                .payload(payload)
                                .build()
                )
                .build();
    }

    private String serializePayload(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize payload", e);
        }
    }

    private Object deserializePayload(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize payload", e);
        }
    }
}
