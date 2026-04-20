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
import ru.configplatform.configserver.dto.UpdateConfigRequest;
import ru.configplatform.configserver.exception.ConfigNotFoundException;
import ru.configplatform.configserver.exception.VersionConflictException;
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
import ru.configplatform.configserver.validation.PayloadValidator;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConfigService {

    private final ServiceRepository serviceRepository;
    private final EnvironmentRepository environmentRepository;
    private final ConfigRepository configRepository;
    private final ConfigVersionRepository configVersionRepository;
    private final CentrifugoOutboxRepository centrifugoOutboxRepository;
    private final PayloadValidator payloadValidator;
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
        payloadValidator.validate(request.getValue(), "json");

        ServiceEntity service = serviceRepository
                .findByName(request.getService())
                .orElseGet(() -> serviceRepository.save(
                        ServiceEntity.builder()
                                .name(request.getService())
                                .build()
                ));

        EnvironmentEntity environment = resolveEnvironment(request.getEnv());
        String payloadJson = serializePayload(request.getValue());

        ConfigEntity config = configRepository
                .findByServiceAndEnvironmentAndConfigKey(service, environment, request.getKey())
                .orElseGet(() ->
                        ConfigEntity.builder()
                                .service(service)
                                .environment(environment)
                                .configKey(request.getKey())
                                .currentVersion(0L)
                                .isSecret(request.getIsSecret())
                                .build()
                );

        if (request.getExpectedVersion() != null) {
            checkVersion(request.getExpectedVersion(), config.getCurrentVersion());
        } else if (config.getCurrentVersion() != 0L) {
            throw new VersionConflictException(0L, config.getCurrentVersion());
        }

        if (!config.isActive()) {
            config.setStatus("active");
            config.setDeletedAt(null);
        }

        long newVersion = config.getCurrentVersion() + 1;
        config.setCurrentVersion(newVersion);
        config = configRepository.save(config);

        String changeType = newVersion == 1 ? "create" : "update";
        createVersionRecord(config, newVersion, payloadJson, changeType);

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
     * Получить конфиг по его идентификатору.
     */
    @Transactional(readOnly = true)
    public ConfigResponse getById(UUID id) {
        ConfigEntity config = configRepository.findByIdAndStatus(id, "active")
                .orElseThrow(() -> new ConfigNotFoundException(id));

        Object payload = loadLatestPayload(config);
        return toResponse(config, payload);
    }

    /**
     * Получить конфиги по имени сервиса и окружению.
     */
    @Transactional(readOnly = true)
    public ConfigListResponse getConfigs(String serviceName, String envCode) {
        ServiceEntity service = serviceRepository.findByName(serviceName).orElse(null);

        if (service == null) {
            return ConfigListResponse.builder()
                    .configs(List.of())
                    .build();
        }

        EnvironmentEntity environment = resolveEnvironment(envCode);

        List<ConfigResponse> responses = configRepository
                .findByServiceAndEnvironmentAndStatus(service, environment, "active")
                .stream()
                .map(config -> {
                    Object payload = loadLatestPayload(config);
                    return toResponse(config, payload);
                })
                .toList();

        return ConfigListResponse.builder()
                .configs(responses)
                .build();
    }

    /**
     * Обновить конфиг по его идентификатору.
     */
    @Transactional
    public ConfigResponse updateById(UUID id, UpdateConfigRequest request) {
        ConfigEntity config = configRepository.findByIdAndStatus(id, "active")
                .orElseThrow(() -> new ConfigNotFoundException(id));

        checkVersion(request.getExpectedVersion(), config.getCurrentVersion());

        payloadValidator.validate(request.getValue(), "json");
        String payloadJson = serializePayload(request.getValue());

        long newVersion = config.getCurrentVersion() + 1;
        config.setCurrentVersion(newVersion);
        config = configRepository.save(config);

        createVersionRecord(config, newVersion, payloadJson, "update");

        publishToCentrifugoOutbox(
                config.getService().getName(),
                config.getEnvironment().getCode(),
                config.getConfigKey(),
                newVersion,
                request.getValue()
        );

        return toResponse(config, request.getValue());
    }

    /**
     * Удалить конфиг по его идентификатору.
     */
    @Transactional
    public void deleteById(UUID id, Long expectedVersion) {
        ConfigEntity config = configRepository.findByIdAndStatus(id, "active")
                .orElseThrow(() -> new ConfigNotFoundException(id));

        checkVersion(expectedVersion, config.getCurrentVersion());

        config.markDeleted();

        long newVersion = config.getCurrentVersion() + 1;
        config.setCurrentVersion(newVersion);
        configRepository.save(config);

        createVersionRecord(config, newVersion, serializePayload(null), "delete");

        Map<String, Object> deleteData = Map.of(
                "key", config.getConfigKey(),
                "version", newVersion,
                "deleted", true
        );

        publishRawToCentrifugo(config.getService().getName(), config.getEnvironment().getCode(), deleteData);
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

    private void checkVersion(long expectedVersion, long actualVersion) {
        if (expectedVersion != actualVersion) {
            throw new VersionConflictException(expectedVersion, actualVersion);
        }
    }

    private EnvironmentEntity resolveEnvironment(String envCode) {
        return environmentRepository.findByCode(envCode)
                .orElseThrow(
                        () -> new IllegalArgumentException(
                                "Unknown environment: " + envCode + ". Valid values: dev, stage, prod"
                        )
                );
    }

    private void createVersionRecord(ConfigEntity config, long version,
                                     String payloadJson, String changeType) {
        ConfigVersionEntity versionEntity = ConfigVersionEntity.builder()
                .config(config)
                .version(version)
                .payload(payloadJson)
                .changeType(changeType)
                .build();
        configVersionRepository.save(versionEntity);
    }

    private Object loadLatestPayload(ConfigEntity config) {
        return configVersionRepository
                .findByConfigIdAndVersion(config.getId(), config.getCurrentVersion())
                .map(v -> deserializePayload(v.getPayload()))
                .orElse(null);
    }

    private void publishToCentrifugoOutbox(String serviceName, String envCode,
                                     String key, long version, Object payload) {
        Map<String, Object> data = Map.of(
                "key", key,
                "version", version,
                "payload", payload
        );
        publishRawToCentrifugo(serviceName, envCode, data);
    }

    private void publishRawToCentrifugo(String serviceName, String envCode,
                                        Map<String, Object> data) {
        String channel = "service:" + serviceName + ":" + envCode;

        Map<String, Object> centrifugoPayload = Map.of(
                "channel", channel,
                "data", data
        );

        CentrifugoOutboxEntity outbox = CentrifugoOutboxEntity.builder()
                .method("publish")
                .payload(serializePayload(centrifugoPayload))
                .partition(0)
                .build();

        centrifugoOutboxRepository.save(outbox);
    }

    private ConfigResponse toResponse(ConfigEntity config, Object payload) {
        return ConfigResponse.builder()
                .id(config.getId())
                .configKey(config.getConfigKey())
                .service(config.getService().getName())
                .environment(config.getEnvironment().getCode())
                .isSecret(config.getIsSecret())
                .status(config.getStatus())
                .currentVersion(config.getCurrentVersion().intValue())
                .latestVersion(
                        ConfigResponse.LatestVersion.builder()
                                .payload(payload)
                                .build()
                )
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .deletedAt(config.getDeletedAt())
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
