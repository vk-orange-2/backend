package ru.configplatform.configserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.configplatform.configserver.dto.*;
import ru.configplatform.configserver.exception.*;
import ru.configplatform.configserver.model.ConfigEntity;
import ru.configplatform.configserver.model.ConfigVersionEntity;
import ru.configplatform.configserver.model.EnvironmentEntity;
import ru.configplatform.configserver.model.ServiceEntity;
import ru.configplatform.configserver.repository.ConfigRepository;
import ru.configplatform.configserver.repository.ConfigVersionRepository;
import ru.configplatform.configserver.repository.EnvironmentRepository;
import ru.configplatform.configserver.repository.RolloutRepository;
import ru.configplatform.configserver.repository.ServiceRepository;
import ru.configplatform.configserver.validation.PayloadValidator;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConfigService {

    private final ServiceRepository serviceRepository;
    private final EnvironmentRepository environmentRepository;
    private final ConfigRepository configRepository;
    private final ConfigVersionRepository configVersionRepository;
    private final RolloutRepository rolloutRepository;
    private final PayloadValidator payloadValidator;
    private final DiffService diffService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /**
     * Создает новый конфиг или обновляет существующий (upsert по service+env+key)
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

        // Блокировка при активном rollout
        if (config.getId() != null) {
            checkNoActiveRollout(config.getId());
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

        return toResponse(config, request.getValue());
    }

    @Transactional(readOnly = true)
    public ConfigResponse getById(UUID id) {
        ConfigEntity config = configRepository.findByIdAndStatus(id, "active")
                .orElseThrow(() -> new ConfigNotFoundException(id));
        Object payload = loadLatestPayload(config);
        return toResponse(config, payload);
    }

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

    @Transactional
    public ConfigResponse updateById(UUID id, UpdateConfigRequest request, RequestContext ctx) {
        ConfigEntity config = configRepository.findByIdAndStatus(id, "active")
                .orElseThrow(() -> new ConfigNotFoundException(id));

        checkVersion(request.getExpectedVersion(), config.getCurrentVersion());
        checkNoActiveRollout(config.getId());

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

        return toResponse(config, request.getValue());
    }

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
    }

    @Transactional(readOnly = true)
    public VersionHistoryResponse getVersionHistory(UUID configId) {
        configRepository.findById(configId)
                .orElseThrow(() -> new ConfigNotFoundException(configId));

        List<VersionResponse> versions = configVersionRepository
                .findByConfigIdOrderByVersionDesc(configId)
                .stream()
                .map(this::toVersionResponse)
                .toList();

        return VersionHistoryResponse.builder().versions(versions).build();
    }

    @Transactional(readOnly = true)
    public VersionResponse getVersion(UUID configId, long version) {
        configRepository.findById(configId)
                .orElseThrow(() -> new ConfigNotFoundException(configId));

        ConfigVersionEntity versionEntity = configVersionRepository
                .findByConfigIdAndVersion(configId, version)
                .orElseThrow(() -> new VersionNotFoundException(configId, version));

        return toVersionResponse(versionEntity);
    }

    @Transactional(readOnly = true)
    public DiffResponse getDiff(UUID configId, long versionFrom, long versionTo) {
        configRepository.findById(configId)
                .orElseThrow(() -> new ConfigNotFoundException(configId));

        String payloadFrom = getPayloadForVersion(configId, versionFrom);
        String payloadTo = getPayloadForVersion(configId, versionTo);

        return diffService.computeDiff(payloadFrom, payloadTo, versionFrom, versionTo);
    }

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

    /**
     * Создать новый сервис явно.
     * Если сервис с таким именем уже существует — возвращает 409 CONFLICT.
     */
    @Transactional
    public ServiceResponse createService(CreateServiceRequest request) {
        serviceRepository.findByName(request.getName()).ifPresent(existing -> {
            throw new ServiceAlreadyExistsException(request.getName());
        });

        ServiceEntity service = serviceRepository.save(
                ServiceEntity.builder()
                        .name(request.getName())
                        .description(request.getDescription())
                        .build()
        );

        return ServiceResponse.builder()
                .id(service.getId())
                .name(service.getName())
                .description(service.getDescription())
                .createdAt(service.getCreatedAt())
                .build();
    }

    private void checkVersion(long expectedVersion, long actualVersion) {
        if (expectedVersion != actualVersion) {
            throw new VersionConflictException(expectedVersion, actualVersion);
        }
    }

    private void checkNoActiveRollout(UUID configId) {
        rolloutRepository.findActiveByConfigId(configId).ifPresent(rollout -> {
            throw new ActiveRolloutExistsException(configId, rollout.getId());
        });
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
