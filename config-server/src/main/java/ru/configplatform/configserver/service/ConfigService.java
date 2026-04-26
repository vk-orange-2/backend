package ru.configplatform.configserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.configplatform.configserver.dto.ConfigListResponse;
import ru.configplatform.configserver.dto.ConfigResponse;
import ru.configplatform.configserver.dto.CreateConfigRequest;
import ru.configplatform.configserver.dto.DiffResponse;
import ru.configplatform.configserver.dto.RequestContext;
import ru.configplatform.configserver.dto.RollbackRequest;
import ru.configplatform.configserver.dto.ServiceResponse;
import ru.configplatform.configserver.dto.UpdateConfigRequest;
import ru.configplatform.configserver.dto.VersionHistoryResponse;
import ru.configplatform.configserver.dto.VersionResponse;
import ru.configplatform.configserver.exception.ConfigNotFoundException;
import ru.configplatform.configserver.exception.VersionConflictException;
import ru.configplatform.configserver.exception.VersionNotFoundException;
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

import java.time.Instant;
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
    private final DiffService diffService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /**
     * Создает новый конфиг или обновляет существующий (upsert по service+env+key)
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
    public ConfigResponse createOrUpdate(CreateConfigRequest request, RequestContext ctx) {
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

        long previousVersion = config.getCurrentVersion();
        long newVersion = previousVersion + 1;
        boolean isCreate = previousVersion == 0;
        String changeType = isCreate ? "create" : "update";

        config.setCurrentVersion(newVersion);
        config = configRepository.save(config);

        createVersionRecord(config, newVersion, payloadJson, changeType, ctx.getActor(), null);

        String diffJson = null;
        if (!isCreate) {
            String oldPayload = getPayloadForVersion(config.getId(), previousVersion);
            DiffResponse diff = diffService.computeDiff(oldPayload, payloadJson, previousVersion, newVersion);
            diffJson = diffService.serializeDiff(diff);
        }

        auditService.log(
                config,
                isCreate ? "CREATE" : "UPDATE",
                isCreate ? null : previousVersion,
                newVersion, 
                diffJson, 
                ctx
        );

        publishToCentrifugoOutbox(
                config, 
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
    public ConfigResponse updateById(UUID id, UpdateConfigRequest request, RequestContext ctx) {
        ConfigEntity config = configRepository.findByIdAndStatus(id, "active")
                .orElseThrow(() -> new ConfigNotFoundException(id));

        checkVersion(request.getExpectedVersion(), config.getCurrentVersion());

        payloadValidator.validate(request.getValue(), "json");

        String payloadJson = serializePayload(request.getValue());

        long previousVersion = config.getCurrentVersion();
        long newVersion = previousVersion + 1;

        config.setCurrentVersion(newVersion);
        config = configRepository.save(config);

        createVersionRecord(config, newVersion, payloadJson, "update", ctx.getActor(), null);

        String oldPayload = getPayloadForVersion(config.getId(), previousVersion);
        DiffResponse diff = diffService.computeDiff(oldPayload, payloadJson, previousVersion, newVersion);
        String diffJson = diffService.serializeDiff(diff);

        auditService.log(config, "UPDATE", previousVersion, newVersion, diffJson, ctx);

        publishToCentrifugoOutbox(
                config,
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
    public void deleteById(UUID id, Long expectedVersion, RequestContext ctx) {
        ConfigEntity config = configRepository.findByIdAndStatus(id, "active")
                .orElseThrow(() -> new ConfigNotFoundException(id));

        checkVersion(expectedVersion, config.getCurrentVersion());

        config.markDeleted();

        long previousVersion = config.getCurrentVersion();
        long newVersion = previousVersion + 1;

        config.setCurrentVersion(newVersion);
        configRepository.save(config);

        createVersionRecord(config, newVersion, serializePayload(null), "delete", ctx.getActor(), null);

        auditService.log(config, "DELETE", previousVersion, newVersion, null, ctx);

        Map<String, Object> deleteData = Map.of(
                "key", config.getConfigKey(),
                "version", newVersion,
                "deleted", true,
                "timestamp", Instant.now().toString()
        );

        publishRawToCentrifugo(
                config.getId(), 
                config.getService().getName(), 
                config.getEnvironment().getCode(), 
                newVersion, 
                deleteData
        );
    }

    /**
     * Возвращает полную историю версий конфигурации.
     * Отсортирована по убыванию номера версии.
     */
    @Transactional(readOnly = true)
    public VersionHistoryResponse getVersionHistory(UUID configId) {
        ConfigEntity config = configRepository.findById(configId)
                .orElseThrow(() -> new ConfigNotFoundException(configId));

        List<VersionResponse> versions = configVersionRepository
                .findByConfigIdOrderByVersionDesc(configId)
                .stream()
                .map(this::toVersionResponse)
                .toList();

        return VersionHistoryResponse.builder().versions(versions).build();
    }

    /**
     * Возвращает конкретную версию конфигурации.
     */
    @Transactional(readOnly = true)
    public VersionResponse getVersion(UUID configId, long version) {
        configRepository.findById(configId)
                .orElseThrow(() -> new ConfigNotFoundException(configId));

        ConfigVersionEntity versionEntity = configVersionRepository
                .findByConfigIdAndVersion(configId, version)
                .orElseThrow(() -> new VersionNotFoundException(configId, version));

        return toVersionResponse(versionEntity);
    }

    /**
     * Вычисляет diff между двумя версиями конфигурации.
     */
    @Transactional(readOnly = true)
    public DiffResponse getDiff(UUID configId, long versionFrom, long versionTo) {
        configRepository.findById(configId)
                .orElseThrow(() -> new ConfigNotFoundException(configId));

        String payloadFrom = getPayloadForVersion(configId, versionFrom);
        String payloadTo = getPayloadForVersion(configId, versionTo);

        return diffService.computeDiff(payloadFrom, payloadTo, versionFrom, versionTo);
    }

    /**
     * Откатывает конфигурацию к указанной версии.
     *
     * Rollback создаёт НОВУЮ версию с payload из targetVersion. Старые версии не модифицируются.
     */
    @Transactional
    public ConfigResponse rollback(UUID configId, RollbackRequest request, RequestContext ctx) {
        ConfigEntity config = configRepository.findByIdAndStatus(configId, "active")
                .orElseThrow(() -> new ConfigNotFoundException(configId));

        checkVersion(request.getExpectedVersion(), config.getCurrentVersion());

        ConfigVersionEntity targetVersion = configVersionRepository
                .findByConfigIdAndVersion(configId, request.getTargetVersion())
                .orElseThrow(() -> new VersionNotFoundException(configId,
                        request.getTargetVersion()));

        String targetPayload = targetVersion.getPayload();

        long previousVersion = config.getCurrentVersion();
        long newVersion = previousVersion + 1;

        config.setCurrentVersion(newVersion);
        config = configRepository.save(config);

        String comment = request.getComment() != null
                ? request.getComment()
                : "Rollback to version " + request.getTargetVersion();

        createVersionRecord(config, newVersion, targetPayload, "rollback", ctx.getActor(), comment);

        String currentPayload = getPayloadForVersion(configId, previousVersion);
        DiffResponse diff = diffService.computeDiff(currentPayload, targetPayload, previousVersion, newVersion);
        String diffJson = diffService.serializeDiff(diff);

        auditService.log(config, "ROLLBACK", previousVersion, newVersion, diffJson, ctx);

        Object payloadObj = deserializePayload(targetPayload);
        publishToCentrifugoOutbox(
                config,
                config.getService().getName(),
                config.getEnvironment().getCode(),
                config.getConfigKey(), 
                newVersion, 
                payloadObj
        );

        return toResponse(config, payloadObj);
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
                                     String payloadJson, String changeType,
                                     String author, String comment) {
        ConfigVersionEntity versionEntity = ConfigVersionEntity.builder()
                .config(config)
                .version(version)
                .payload(payloadJson)
                .changeType(changeType)
                .author(author)
                .comment(comment)
                .build();
        configVersionRepository.save(versionEntity);
    }

    private String getPayloadForVersion(UUID configId, long version) {
        return configVersionRepository
                .findByConfigIdAndVersion(configId, version)
                .map(ConfigVersionEntity::getPayload)
                .orElse(null);
    }

    private Object loadLatestPayload(ConfigEntity config) {
        return configVersionRepository
                .findByConfigIdAndVersion(config.getId(), config.getCurrentVersion())
                .map(v -> deserializePayload(v.getPayload()))
                .orElse(null);
    }

    private void publishToCentrifugoOutbox(
            ConfigEntity config, String serviceName,
            String envCode, String key,
            long version, Object payload
    ) {
        Map<String, Object> data = Map.of(
                "configId", config.getId().toString(),
                "key", key,
                "version", version,
                "payload", payload,
                "timestamp", Instant.now().toString()
        );
        publishRawToCentrifugo(config.getId(), serviceName, envCode, version, data);
    }

    private void publishRawToCentrifugo(
            UUID configId, String serviceName,
            String envCode, long version,
            Map<String, Object> data
    ) {
        String channel = "service:" + serviceName + ":" + envCode;
        String idempotencyKey = configId + ":v" + version;

        Map<String, Object> centrifugoPayload = Map.of(
                "channel", channel,
                "data", data
        );

        CentrifugoOutboxEntity outbox = CentrifugoOutboxEntity.builder()
                .method("publish")
                .payload(serializePayload(centrifugoPayload))
                .partition(0)
                .idempotencyKey(idempotencyKey)
                .build();

        try {
            centrifugoOutboxRepository.save(outbox);
        } catch (DataIntegrityViolationException e) {
            // Идемпотентность: запись с таким ключом уже существует
            // Это нормальная ситуация при повторной обработке — просто игнорируем
        }
    }

    private VersionResponse toVersionResponse(ConfigVersionEntity entity) {
        return VersionResponse.builder()
                .id(entity.getId())
                .configId(entity.getConfig().getId())
                .version(entity.getVersion())
                .payload(deserializePayload(entity.getPayload()))
                .changeType(entity.getChangeType())
                .author(entity.getAuthor())
                .comment(entity.getComment())
                .createdAt(entity.getCreatedAt())
                .build();
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
        if (json == null || "null".equals(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize payload", e);
        }
    }
}
