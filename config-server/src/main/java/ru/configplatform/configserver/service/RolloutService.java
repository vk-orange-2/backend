package ru.configplatform.configserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.configplatform.configserver.dto.*;
import ru.configplatform.configserver.exception.*;
import ru.configplatform.configserver.metrics.DeliveryMetrics;
import ru.configplatform.configserver.model.*;
import ru.configplatform.configserver.repository.CentrifugoOutboxRepository;
import ru.configplatform.configserver.repository.ConfigRepository;
import ru.configplatform.configserver.repository.ConfigVersionRepository;
import ru.configplatform.configserver.repository.RolloutRepository;
import ru.configplatform.configserver.service.lock.DistributedLockService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RolloutService {

    private static final String ENCRYPTED_MARKER = "encrypted";

    private final RolloutRepository rolloutRepository;
    private final ConfigRepository configRepository;
    private final ConfigVersionRepository configVersionRepository;
    private final CentrifugoOutboxRepository centrifugoOutboxRepository;
    private final DistributedLockService distributedLockService;
    private final AuditService auditService;
    private final DiffService diffService;
    private final ObjectMapper objectMapper;
    private final DeliveryMetrics deliveryMetrics;
    private final ObservationRegistry observationRegistry;
    private final EncryptionService encryptionService;

    /**
     * Создать и запустить rollout.
     *
     * Для instant — сразу публикуем в основной канал, статус completed
     * Для gradual — публикуем сообщение о начале gradual rollout,
     *               первый deployment отправляется сразу, остальные по расписанию
     * Для canary  — публикуем canary_deploy в основной канал, статус completed
     *               (canary "стоит" на месте, пока не будет promote или rollback)
     */
    @Observed(
            name = "rollout.create",
            contextualName = "create-rollout"
    )
    @Transactional
    public RolloutResponse createAndStart(CreateRolloutRequest request, RequestContext ctx) {
        ConfigEntity config = findActiveConfig(request.getConfigId());

        String serviceName = config.getService().getName();
        String envCode = config.getEnvironment().getCode();

        // Serialize rollout creation for same service+env
        String lockKey = "rollout:" + serviceName + ":" + envCode;
        distributedLockService.acquireTransactionLock(lockKey);

        rolloutRepository.findActiveByConfigId(config.getId()).ifPresent(existing -> {
            throw new ActiveRolloutExistsException(config.getId(), existing.getId());
        });

        RolloutType type = RolloutType.fromValue(request.getType());

        // Валидация canary percentage
        if (type == RolloutType.CANARY) {
            if (request.getCanaryPercentage() == null) {
                throw new IllegalArgumentException("canaryPercentage is required for canary rollout");
            }
        }

        validateCanaryPolicy(config, type, request.getCanaryPercentage(), request.getTotalDeployments(), serviceName, envCode);

        long targetVersion = config.getCurrentVersion();
        long baseline = determineBaseline(config.getId(), type, targetVersion);

        String key = config.getConfigKey();

        ConfigVersionEntity targetVersionEntity = configVersionRepository
                .findByConfigIdAndVersion(config.getId(), targetVersion)
                .orElseThrow(() -> new IllegalStateException("Target version not found"));

        String decryptedPayload = getPlainPayloadForVersion(config.getId(), targetVersion, config.getIsSecret());
        Object payload = deserializePayload(decryptedPayload);

        RolloutEntity rollout;

        switch (type) {
            case INSTANT -> {
                rollout = RolloutEntity.builder()
                        .config(config)
                        .type(type)
                        .status(RolloutStatus.COMPLETED)
                        .baselineVersion(baseline)
                        .targetVersion(targetVersion)
                        .totalDeployments(1)
                        .currentDeployment(1)
                        .deploymentIntervalSeconds(0)
                        .startedAt(Instant.now())
                        .completedAt(Instant.now())
                        .build();
                rollout = rolloutRepository.save(rollout);

                publishInstantUpdate(config, serviceName, envCode, key, targetVersion, payload, rollout.getId());
                markCanarySupersededForConfig(config.getId(), rollout.getId());
            }
            case CANARY -> {
                rollout = RolloutEntity.builder()
                        .config(config)
                        .type(type)
                        .status(RolloutStatus.COMPLETED)
                        .baselineVersion(baseline)
                        .targetVersion(targetVersion)
                        .totalDeployments(1)
                        .currentDeployment(1)
                        .deploymentIntervalSeconds(0)
                        .canaryPercentage(request.getCanaryPercentage())
                        .startedAt(Instant.now())
                        .completedAt(Instant.now())
                        .build();
                rollout = rolloutRepository.save(rollout);

                publishCanaryDeploy(config, serviceName, envCode, key, targetVersion, payload,
                        request.getCanaryPercentage(), rollout.getId());

                markCanarySupersededForConfig(config.getId(), rollout.getId());
            }
            case GRADUAL -> {
                int totalDeployments = request.getTotalDeployments() != null ? request.getTotalDeployments() : 1;
                int intervalSeconds = request.getDeploymentIntervalSeconds() != null
                        ? request.getDeploymentIntervalSeconds() : 60;

                rollout = RolloutEntity.builder()
                        .config(config)
                        .type(type)
                        .status(RolloutStatus.IN_PROGRESS)
                        .baselineVersion(baseline)
                        .targetVersion(targetVersion)
                        .totalDeployments(totalDeployments)
                        .currentDeployment(0)
                        .deploymentIntervalSeconds(intervalSeconds)
                        .startedAt(Instant.now())
                        .build();
                rollout = rolloutRepository.save(rollout);

                executeNextDeployment(rollout, config, serviceName, envCode, key, targetVersion, payload);

                markCanarySupersededForConfig(config.getId(), rollout.getId());
            }
            default -> throw new IllegalArgumentException("Unsupported rollout type: " + type);
        }

        Map<String, Object> auditData = new LinkedHashMap<>();
        auditData.put("rolloutId", rollout.getId().toString());
        auditData.put("type", rollout.getType().getValue());
        auditData.put("totalDeployments", rollout.getTotalDeployments());
        if (rollout.getCanaryPercentage() != null) {
            auditData.put("canaryPercentage", rollout.getCanaryPercentage());
        }

        auditService.log(config, "ROLLOUT_START", baseline, targetVersion,
                serializePayload(auditData), ctx);

        return toResponse(rollout);
    }

    /**
     * Получить rollout по ID
     */
    @Observed(
            name = "rollout.get_by_id",
            contextualName = "get-rollout-by-id"
    )
    @Transactional(readOnly = true)
    public RolloutResponse getById(UUID rolloutId) {
        RolloutEntity rollout = rolloutRepository.findById(rolloutId)
                .orElseThrow(() -> new RolloutNotFoundException(rolloutId));
        ensureConfigActive(rollout.getConfig());
        return toResponse(rollout);
    }

    /**
     * Список rollout-ов для конфига
     */
    @Observed(
            name = "rollout.get_by_config_id",
            contextualName = "get-rollouts"
    )
    @Transactional(readOnly = true)
    public List<RolloutResponse> getByConfigId(UUID configId) {
        ConfigEntity config = findActiveConfig(configId);
        return rolloutRepository.findByConfigIdOrderByCreatedAtDesc(config.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Получить все активные rollout-ы для service+environment.
     */
    @Observed(
            name = "rollout.get_active",
            contextualName = "get-active-rollouts"
    )
    @Transactional(readOnly = true)
    public List<RolloutResponse> getActiveByServiceAndEnvironment(String serviceName, String envCode) {
        return rolloutRepository.findActiveByServiceAndEnvironment(serviceName, envCode)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Остановить rollout
     */
    @Observed(
            name = "rollout.stop",
            contextualName = "stop-rollout"
    )
    @Transactional
    public RolloutResponse stop(UUID rolloutId, RequestContext ctx) {
        RolloutEntity rollout = rolloutRepository.findById(rolloutId)
                .orElseThrow(() -> new RolloutNotFoundException(rolloutId));

        ensureConfigActive(rollout.getConfig());

        if (!rollout.isActive()) {
            throw new RolloutNotActiveException(rolloutId, rollout.getStatus().getValue());
        }

        ConfigEntity config = rollout.getConfig();
        rollout.markStopped();
        rolloutRepository.save(rollout);

        auditService.log(config, "ROLLOUT_STOP", rollout.getBaselineVersion(),
                rollout.getTargetVersion(),
                serializePayload(Map.of(
                        "rolloutId", rollout.getId().toString(),
                        "stoppedAtDeployment", rollout.getCurrentDeployment(),
                        "totalDeployments", rollout.getTotalDeployments()
                )), ctx);

        return toResponse(rollout);
    }

    /**
     * Rollback rollout
     */
    @Observed(
            name = "rollout.rollback",
            contextualName = "rollback-rollout"
    )
    @Transactional
    public RolloutResponse rollback(UUID rolloutId, RollbackRolloutRequest request, RequestContext ctx) {
        RolloutEntity rollout = rolloutRepository.findById(rolloutId)
                .orElseThrow(() -> new RolloutNotFoundException(rolloutId));

        ConfigEntity config = rollout.getConfig();
        ensureConfigActive(config);

        boolean isCanaryCompleted = rollout.getType() == RolloutType.CANARY
                && rollout.getStatus() == RolloutStatus.COMPLETED;

        if (!rollout.isActive() && !isCanaryCompleted) {
            throw new RolloutNotActiveException(rolloutId, rollout.getStatus().getValue());
        }

        long rollbackToVersion;

        if (request != null && request.getTargetVersion() != null) {
            if (!isCanaryCompleted && rollout.getType() != RolloutType.CANARY) {
                throw new IllegalArgumentException("Custom targetVersion is only supported for canary rollouts");
            }

            long requestedVersion = request.getTargetVersion();
            configVersionRepository.findByConfigIdAndVersion(config.getId(), requestedVersion)
                    .orElseThrow(() -> new VersionNotFoundException(config.getId(), requestedVersion));

            rollbackToVersion = requestedVersion;
        } else {
            rollbackToVersion = rollout.getBaselineVersion();
        }

        return executeRollback(rollout, config, rollbackToVersion,
                request != null ? request.getComment() : null, ctx);
    }

    /**
     * Rollback a config to a specific version by creating an instant rollout.
     */
    @Observed(
            name = "rollout.rollback.config",
            contextualName = "rollback-config"
    )
    @Transactional
    public RolloutResponse rollbackConfig(UUID configId, RollbackRequest request,
                                          RequestContext ctx) {
        ConfigEntity config = findActiveConfig(configId);

        if (!Objects.equals(request.getExpectedVersion(), config.getCurrentVersion())) {
            throw new VersionConflictException(
                    request.getExpectedVersion(), config.getCurrentVersion());
        }

        rolloutRepository.findActiveByConfigId(configId).ifPresent(existing -> {
            throw new ActiveRolloutExistsException(configId, existing.getId());
        });

        long rollbackToVersion = request.getTargetVersion();

        ConfigVersionEntity rollbackVersionEntity = configVersionRepository
                .findByConfigIdAndVersion(configId, rollbackToVersion)
                .orElseThrow(() -> new VersionNotFoundException(configId, rollbackToVersion));

        String rollbackPayloadPlain = getPlainPayloadForVersion(configId, rollbackToVersion, config.getIsSecret());

        long previousVersion = config.getCurrentVersion();
        long newVersion = previousVersion + 1;

        config.setCurrentVersion(newVersion);
        configRepository.save(config);

        String comment = request != null && request.getComment() != null
                ? request.getComment()
                : "Rollback to version " + rollbackToVersion;

        String payloadToStore = buildStoredPayload(config.getIsSecret(), rollbackPayloadPlain);
        ConfigVersionEntity newVersionEntity = ConfigVersionEntity.builder()
                .config(config)
                .version(newVersion)
                .payload(payloadToStore)
                .changeType("rollback")
                .author(ctx.getActor())
                .comment(comment)
                .build();
        configVersionRepository.save(newVersionEntity);

        // Вычисляем diff на основе расшифрованных данных
        String currentPayloadPlain = getPlainPayloadForVersion(configId, previousVersion, config.getIsSecret());
        DiffResponse diff = diffService.computeDiff(currentPayloadPlain, rollbackPayloadPlain, previousVersion, newVersion);
        String diffJson = diffService.serializeDiff(diff);

        // Публикуем в основной канал (всем клиентам)
        String serviceName = config.getService().getName();
        String envCode = config.getEnvironment().getCode();

        // Determine baseline
        long baseline = determineBaseline(configId, RolloutType.INSTANT, newVersion);

        // Create instant rollout for the rollback
        RolloutEntity rollout = RolloutEntity.builder()
                .config(config)
                .type(RolloutType.INSTANT)
                .status(RolloutStatus.COMPLETED)
                .baselineVersion(baseline)
                .targetVersion(newVersion)
                .totalDeployments(1)
                .currentDeployment(1)
                .deploymentIntervalSeconds(0)
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .build();
        rollout = rolloutRepository.save(rollout);

        Object payloadObj = deserializePayload(rollbackPayloadPlain);
        publishInstantUpdate(config, serviceName, envCode, config.getConfigKey(),
                newVersion, payloadObj, rollout.getId());

        // Supersede canary if any
        markCanarySupersededForConfig(configId, rollout.getId());

        // Audit
        auditService.log(config, "ROLLBACK", previousVersion, newVersion, diffJson, ctx);

        Map<String, Object> auditData = new LinkedHashMap<>();
        auditData.put("rolloutId", rollout.getId().toString());
        auditData.put("rollbackToVersion", rollbackToVersion);
        auditData.put("newVersion", newVersion);
        auditData.put("configRollback", true);

        auditService.log(config, "ROLLOUT_START", baseline, newVersion,
                serializePayload(auditData), ctx);

        return toResponse(rollout);
    }

    /**
     * Выполнить следующий deployment для gradual rollout (вызывается scheduler-ом или вручную)
     */
    @Observed(
            name = "rollout.deploy.next",
            contextualName = "deploy-next"
    )
    @Transactional
    public RolloutResponse deployNext(UUID rolloutId, RequestContext ctx) {
        RolloutEntity rollout = rolloutRepository.findById(rolloutId)
                .orElseThrow(() -> new RolloutNotFoundException(rolloutId));

        ensureConfigActive(rollout.getConfig());

        if (rollout.getStatus() != RolloutStatus.IN_PROGRESS) {
            throw new RolloutNotActiveException(rolloutId, rollout.getStatus().getValue());
        }

        if (rollout.getCurrentDeployment() >= rollout.getTotalDeployments()) {
            throw new IllegalStateException("All deployments already completed for rollout " + rolloutId);
        }

        ConfigEntity config = rollout.getConfig();
        String serviceName = config.getService().getName();
        String envCode = config.getEnvironment().getCode();
        String key = config.getConfigKey();

        String decryptedPayload = getPlainPayloadForVersion(config.getId(), rollout.getTargetVersion(), config.getIsSecret());
        Object payload = deserializePayload(decryptedPayload);

        executeNextDeployment(rollout, config, serviceName, envCode, key,
                rollout.getTargetVersion(), payload);

        rolloutRepository.save(rollout);

        if (rollout.getStatus() == RolloutStatus.COMPLETED) {
            auditService.log(config, "ROLLOUT_COMPLETE", rollout.getBaselineVersion(),
                    rollout.getTargetVersion(),
                    serializePayload(Map.of(
                            "rolloutId", rollout.getId().toString(),
                            "totalDeployments", rollout.getTotalDeployments()
                    )), ctx);
        }

        return toResponse(rollout);
    }

    /**
     * Вызывается scheduler-ом для продвижения всех готовых gradual rollout-ов
     */
    @Observed(
            name = "rollout.scheduler.process",
            contextualName = "process-scheduled-rollouts"
    )
    @Transactional
    public void processScheduledDeployments() {
        List<RolloutEntity> ready = rolloutRepository.findReadyForNextDeployment(Instant.now());
        for (RolloutEntity rollout : ready) {
            try {
                processSingleRolloutWithRetry(rollout);
            } catch (Exception e) {
                log.error("Failed to process deployment for rollout {}", rollout.getId(), e);
            }
        }
    }

    @Retryable(
            retryFor = RuntimeException.class,
            maxAttempts = 2,
            backoff = @Backoff(
                    delay = 1000,
                    multiplier = 2
            )
    )
    @Transactional
    private void processSingleRolloutWithRetry(RolloutEntity rollout) {
        ConfigEntity config = rollout.getConfig();

        // Skip if config was deleted
        if (!config.isActive()) {
            log.warn("Skipping deployment for rollout {} — config {} is deleted", rollout.getId(), config.getId());
            rollout.markStopped();
            rolloutRepository.save(rollout);
            return;
        }

        String serviceName = config.getService().getName();
        String envCode = config.getEnvironment().getCode();
        String key = config.getConfigKey();

        String decryptedPayload = getPlainPayloadForVersion(config.getId(), rollout.getTargetVersion(), config.getIsSecret());
        if (decryptedPayload == null) {
            log.error("Target version {} not found for rollout {}", rollout.getTargetVersion(), rollout.getId());
            return;
        }

        Object payload = deserializePayload(decryptedPayload);

        executeNextDeployment(rollout, config, serviceName, envCode, key,
                rollout.getTargetVersion(), payload);
        rolloutRepository.save(rollout);

        if (rollout.getStatus() == RolloutStatus.COMPLETED) {
            RequestContext systemCtx = RequestContext.builder()
                    .actor("system-scheduler")
                    .build();
            auditService.log(config, "ROLLOUT_COMPLETE", rollout.getBaselineVersion(),
                    rollout.getTargetVersion(),
                    serializePayload(Map.of(
                            "rolloutId", rollout.getId().toString(),
                            "totalDeployments", rollout.getTotalDeployments()
                    )), systemCtx);
        }
    }

    @Recover
    public void recover(RuntimeException ex, RolloutEntity rollout) {
        log.error(
                "Rollout {} failed after retries",
                rollout.getId(),
                ex
        );
    }

    private ConfigEntity findActiveConfig(UUID configId) {
        return configRepository.findByIdAndStatus(configId, "active")
                .orElseThrow(() -> new ConfigNotFoundException(configId));
    }

    private void ensureConfigActive(ConfigEntity config) {
        if (!config.isActive()) {
            throw new ConfigNotFoundException(config.getId());
        }
    }

    private long determineBaseline(java.util.UUID configId, RolloutType newType, long targetVersion) {
        var existingCanary = rolloutRepository.findCompletedCanaryByConfigId(configId);
        if (existingCanary.isPresent()) {
            RolloutEntity canary = existingCanary.get();
            return canary.getBaselineVersion();
        }
        return targetVersion > 1 ? targetVersion - 1 : 0;
    }

    private void validateCanaryPolicy(ConfigEntity config, RolloutType newType,
                                      Integer newCanaryPercentage, Integer totalDeployments,
                                      String serviceName, String envCode) {
        List<RolloutEntity> completedCanaries = rolloutRepository
                .findCompletedCanaryByServiceEnv(serviceName, envCode);

        if (completedCanaries.isEmpty()) {
            return;
        }

        int existingCanaryPercentage = completedCanaries.get(0).getCanaryPercentage();
        boolean sameConfigHasCanary = completedCanaries.stream()
                .anyMatch(r -> r.getConfig().getId().equals(config.getId()));

        if (sameConfigHasCanary) {
            switch (newType) {
                case INSTANT -> {
                    return;
                }
                case CANARY -> {
                    if (newCanaryPercentage < existingCanaryPercentage) {
                        throw new CanaryPolicyViolationException(
                                "New canary percentage (" + newCanaryPercentage
                                        + ") must be >= existing canary percentage ("
                                        + existingCanaryPercentage + ") for the same config");
                    }
                    return;
                }
                case GRADUAL -> {
                    int stepPercentage = totalDeployments != null && totalDeployments > 0
                            ? 100 / totalDeployments : 100;
                    if (stepPercentage < existingCanaryPercentage) {
                        throw new CanaryPolicyViolationException(
                                "Gradual rollout step size (" + stepPercentage
                                        + "%) must be >= existing canary percentage ("
                                        + existingCanaryPercentage + "%). "
                                        + "Reduce totalDeployments to increase step size.");
                    }
                    return;
                }
                default -> throw new CanaryPolicyViolationException(
                        "Unsupported rollout type: " + newType);
            }
        }
    }

    private void markCanarySupersededForConfig(UUID configId, UUID excludeRolloutId) {
        rolloutRepository.findCompletedCanaryByConfigId(configId).ifPresent(oldCanary -> {
            if (!oldCanary.getId().equals(excludeRolloutId)) {
                oldCanary.markRolledBack();
                rolloutRepository.save(oldCanary);
            }
        });
    }

    private String getPlainPayloadForVersion(UUID configId, long version, boolean isSecret) {
        ConfigVersionEntity entity = configVersionRepository
                .findByConfigIdAndVersion(configId, version)
                .orElse(null);
        if (entity == null) return null;
        String raw = entity.getPayload();
        if (raw == null) return null;
        if (!isSecret) return raw;
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node.has(ENCRYPTED_MARKER) && node.get(ENCRYPTED_MARKER).isTextual()) {
                return encryptionService.decrypt(node.get(ENCRYPTED_MARKER).asText());
            }
            return encryptionService.decrypt(raw);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse encrypted payload wrapper", e);
        }
    }

    private String buildStoredPayload(boolean isSecret, String plainPayloadJson) {
        if (isSecret && plainPayloadJson != null && !"null".equals(plainPayloadJson)) {
            String encrypted = encryptionService.encrypt(plainPayloadJson);
            return String.format("{\"%s\":\"%s\"}", ENCRYPTED_MARKER, encrypted);
        } else {
            return plainPayloadJson;
        }
    }

    private RolloutResponse executeRollback(RolloutEntity rollout, ConfigEntity config,
                                            long rollbackToVersion, String comment,
                                            RequestContext ctx) {
        if (rollbackToVersion == 0) {
            throw new IllegalStateException(
                    "Cannot rollback: target version is 0 (no previous version exists)");
        }

        String rollbackPayloadPlain = getPlainPayloadForVersion(config.getId(), rollbackToVersion, config.getIsSecret());

        long previousVersion = config.getCurrentVersion();
        long newVersion = previousVersion + 1;
        config.setCurrentVersion(newVersion);
        configRepository.save(config);

        String rollbackComment = comment != null
                ? comment
                : "Rollback from rollout " + rollout.getId() + " to version " + rollbackToVersion;

        String payloadToStore = buildStoredPayload(config.getIsSecret(), rollbackPayloadPlain);
        ConfigVersionEntity newVersionEntity = ConfigVersionEntity.builder()
                .config(config)
                .version(newVersion)
                .payload(payloadToStore)
                .changeType("rollback")
                .author(ctx.getActor())
                .comment(rollbackComment)
                .build();
        configVersionRepository.save(newVersionEntity);

        String currentPayloadPlain = getPlainPayloadForVersion(config.getId(), previousVersion, config.getIsSecret());
        DiffResponse diff = diffService.computeDiff(currentPayloadPlain, rollbackPayloadPlain, previousVersion, newVersion);
        String diffJson = diffService.serializeDiff(diff);

        String serviceName = config.getService().getName();
        String envCode = config.getEnvironment().getCode();

        Object payloadObj = deserializePayload(rollbackPayloadPlain);
        publishInstantUpdate(config, serviceName, envCode, config.getConfigKey(),
                newVersion, payloadObj, rollout.getId());

        rollout.markRolledBack();
        rolloutRepository.save(rollout);

        auditService.log(config, "ROLLBACK", previousVersion, newVersion, diffJson, ctx);

        Map<String, Object> auditData = new LinkedHashMap<>();
        auditData.put("rolloutId", rollout.getId().toString());
        auditData.put("rollbackToVersion", rollbackToVersion);
        auditData.put("newVersion", newVersion);
        boolean isCanaryCompleted = rollout.getType() == RolloutType.CANARY
                && rollout.getStatus() == RolloutStatus.COMPLETED;
        if (isCanaryCompleted) {
            auditData.put("canaryRollback", true);
        }

        auditService.log(config, "ROLLOUT_ROLLBACK", rollout.getBaselineVersion(),
                newVersion, serializePayload(auditData), ctx);

        return toResponse(rollout);
    }

    private void executeNextDeployment(RolloutEntity rollout, ConfigEntity config,
                                       String serviceName, String envCode, String key,
                                       long targetVersion, Object payload) {
        rollout.advanceDeployment();
        int deploymentNumber = rollout.getCurrentDeployment();

        String channel = String.format("service:%s:%s", serviceName, envCode);
        String idempotencyKey = String.format("rollout:%s:deploy:%d", rollout.getId(), deploymentNumber);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "gradual_deploy");
        data.put("configId", config.getId().toString());
        data.put("key", key);
        data.put("version", targetVersion);
        data.put("deployment", deploymentNumber);
        data.put("totalDeployments", rollout.getTotalDeployments());
        data.put("payload", payload);
        data.put("rolloutId", rollout.getId().toString());
        data.put("timestamp", Instant.now().toString());

        publishToCentrifugo(channel, data, idempotencyKey);
    }

    private void publishInstantUpdate(ConfigEntity config, String serviceName,
                                      String envCode, String key, long version,
                                      Object payload, UUID rolloutId) {
        String channel = String.format("service:%s:%s", serviceName, envCode);
        String idempotencyKey = config.getId() + ":v" + version;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "update");
        data.put("configId", config.getId().toString());
        data.put("key", key);
        data.put("version", version);
        data.put("payload", payload);
        data.put("rolloutId", rolloutId.toString());
        data.put("timestamp", Instant.now().toString());

        publishToCentrifugo(channel, data, idempotencyKey);
    }

    private void publishCanaryDeploy(ConfigEntity config, String serviceName,
                                     String envCode, String key, long version,
                                     Object payload, int percentage, UUID rolloutId) {
        String channel = String.format("service:%s:%s", serviceName, envCode);
        String idempotencyKey = String.format("canary:%s:v%d", config.getId(), version);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "canary_deploy");
        data.put("configId", config.getId().toString());
        data.put("key", key);
        data.put("version", version);
        data.put("payload", payload);
        data.put("canaryPercentage", percentage);
        data.put("rolloutId", rolloutId.toString());
        data.put("timestamp", Instant.now().toString());

        publishToCentrifugo(channel, data, idempotencyKey);
    }

    private void publishToCentrifugo(String channel, Map<String, Object> data, String idempotencyKey) {
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

        Timer.Sample sample = deliveryMetrics.startEnqueueTimer();
        try {
            Observation.createNotStarted(
                    "centrifugo.outbox.publish",
                    observationRegistry
            )
            .observe(() -> {
                centrifugoOutboxRepository.save(outbox);
            });
            deliveryMetrics.markEnqueueSuccess(sample);
        } catch (DataIntegrityViolationException e) {
            // Идемпотентность: запись с таким ключом уже существует
            deliveryMetrics.markDuplicate(sample);
            log.debug("Duplicate outbox entry for key: {}", idempotencyKey);
        } catch (RuntimeException e) {
            deliveryMetrics.markFailure(sample);
            throw e;
        }
    }

    private RolloutResponse toResponse(RolloutEntity entity) {
        return RolloutResponse.builder()
                .id(entity.getId())
                .configId(entity.getConfig().getId())
                .type(entity.getType().getValue())
                .status(entity.getStatus().getValue())
                .baselineVersion(entity.getBaselineVersion())
                .targetVersion(entity.getTargetVersion())
                .totalDeployments(entity.getTotalDeployments())
                .currentDeployment(entity.getCurrentDeployment())
                .deploymentIntervalSeconds(entity.getDeploymentIntervalSeconds())
                .canaryPercentage(entity.getCanaryPercentage())
                .nextDeploymentAt(entity.getNextDeploymentAt())
                .createdAt(entity.getCreatedAt())
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .stoppedAt(entity.getStoppedAt())
                .rolledBackAt(entity.getRolledBackAt())
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
        if (json == null || "null".equals(json)) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize payload", e);
        }
    }
}
