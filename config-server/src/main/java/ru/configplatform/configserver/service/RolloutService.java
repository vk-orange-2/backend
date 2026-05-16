package ru.configplatform.configserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.configplatform.configserver.dto.*;
import ru.configplatform.configserver.exception.*;
import ru.configplatform.configserver.model.*;
import ru.configplatform.configserver.repository.CentrifugoOutboxRepository;
import ru.configplatform.configserver.repository.ConfigRepository;
import ru.configplatform.configserver.repository.ConfigVersionRepository;
import ru.configplatform.configserver.repository.RolloutRepository;
import ru.configplatform.configserver.service.lock.DistributedLockService;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class RolloutService {

    private final RolloutRepository rolloutRepository;
    private final ConfigRepository configRepository;
    private final ConfigVersionRepository configVersionRepository;
    private final CentrifugoOutboxRepository centrifugoOutboxRepository;
    private final DistributedLockService distributedLockService;
    private final AuditService auditService;
    private final DiffService diffService;
    private final ObjectMapper objectMapper;

    /**
     * Создать и запустить rollout.
     *
     * Для instant — сразу публикуем в основной канал, статус completed
     * Для gradual — публикуем сообщение о начале gradual rollout,
     *               первый deployment отправляется сразу, остальные по расписанию
     * Для canary  — публикуем canary_deploy в основной канал, статус completed
     *               (canary "стоит" на месте, пока не будет promote или rollback)
     */
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

        Object payload = deserializePayload(targetVersionEntity.getPayload());

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

                // Публикуем в основной канал
                publishInstantUpdate(config, serviceName, envCode, key, targetVersion, payload, rollout.getId());

                // Если это instant, который "промоутит" canary — помечаем canary rolled_back
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

                // Публикуем canary deploy в основной канал
                publishCanaryDeploy(config, serviceName, envCode, key, targetVersion, payload,
                        request.getCanaryPercentage(), rollout.getId());

                // Если заменяем предыдущий canary — помечаем старый как rolled_back
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

                // Сразу выполняем первый deployment
                executeNextDeployment(rollout, config, serviceName, envCode, key, targetVersion, payload);

                // Если это gradual, который "промоутит" canary — помечаем canary
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
     * Определяет базовую версию для нового развертывания.
     *
     * Если конфигурация содержит completed canary rollout и мы создаем rollout, не являющийся canary
     * (instant или gradual — т. е. повышение статуса canary version), то в качестве базовой версии должна использоваться базовая версия этого canary rollout
     * это гарантирует, что при откате система вернется к состоянию, предшествовавшему canary rollout
     *
     * При создании нового canary rollout (замещающего старое) также следует унаследовать базовую версию от старого canary rollout,
     * чтобы при откате система возвращалась к состоянию, предшествовавшему canary rollout (а не к промежуточной canary версии)
     *
     * В остальных случаях: базовая версия = целевая версия - 1 (или 0 — для самой первой версии)
     */
    private long determineBaseline(java.util.UUID configId, RolloutType newType, long targetVersion) {
        var existingCanary = rolloutRepository.findCompletedCanaryByConfigId(configId);

        if (existingCanary.isPresent()) {
            RolloutEntity canary = existingCanary.get();
            // For all types: if promoting or replacing canary, use canary's baseline
            // so rollback goes to pre-canary state
            return canary.getBaselineVersion();
        }

        // No canary — standard baseline
        return targetVersion > 1 ? targetVersion - 1 : 0;
    }

    /**
     * Проверяет ограничения политики canary rollout-а
     *
     * При наличии completed canary rollout-ов для сервиса и среды применяются следующие правила:
     *
     * Для той же конфигурации, в которой уже есть canary rollout:
     * - canary rollout с тем же или большим процентом → OK
     * - instant rollout → OK
     * - gradual rollout → OK, но первый шаг должен охватывать как минимум процент canary rollout
     * - rollback canary rollout → OK
     *
     * Для ДРУГОЙ конфигурации в том же сервисе и среде → OK
     *
     * все остальное → ЗАБЛОКИРОВАНО
     */
    private void validateCanaryPolicy(ConfigEntity config, RolloutType newType,
                                      Integer newCanaryPercentage, Integer totalDeployments,
                                      String serviceName, String envCode) {
        List<RolloutEntity> completedCanaries = rolloutRepository
                .findCompletedCanaryByServiceEnv(serviceName, envCode);

        if (completedCanaries.isEmpty()) {
            // Ограничения для canary отсутствуют
            return;
        }

        // Get the canary percentage that's currently in effect (all completed canaries
        // in a service+env must have the same percentage due to our policy)
        int existingCanaryPercentage = completedCanaries.get(0).getCanaryPercentage();

        // Check if this config already has a completed canary
        boolean sameConfigHasCanary = completedCanaries.stream()
                .anyMatch(r -> r.getConfig().getId().equals(config.getId()));

        if (sameConfigHasCanary) {
            switch (newType) {
                case INSTANT:
                    // Promoting canary to all → OK
                    return;
                case CANARY:
                    // New canary on same config: must be same or greater percentage
                    if (newCanaryPercentage < existingCanaryPercentage) {
                        throw new CanaryPolicyViolationException(
                                "New canary percentage (" + newCanaryPercentage
                                        + ") must be >= existing canary percentage ("
                                        + existingCanaryPercentage + ") for the same config");
                    }
                    return;
                case GRADUAL:
                    // Gradual promotion: each step covers 100/totalDeployments percent,
                    // first step must be >= canary percentage
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
                default:
                    throw new CanaryPolicyViolationException(
                            "Unsupported rollout type: " + newType);
            }
        }
    }

    /**
     * Помечает любую ранее completed canary-версию для той же конфигурации как rolled_back
     * (замещенную новым rollout). Пропускает rollout с идентификатором excludeRolloutId.
     */
    private void markCanarySupersededForConfig(UUID configId, UUID excludeRolloutId) {
        rolloutRepository.findCompletedCanaryByConfigId(configId).ifPresent(oldCanary -> {
            if (!oldCanary.getId().equals(excludeRolloutId)) {
                oldCanary.markRolledBack();
                rolloutRepository.save(oldCanary);
            }
        });
    }

    /**
     * Получить rollout по ID
     */
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
     * Используется SDK при реконнекте/старте — один запрос вместо N.
     */
    @Transactional(readOnly = true)
    public List<RolloutResponse> getActiveByServiceAndEnvironment(String serviceName, String envCode) {
        return rolloutRepository.findActiveByServiceAndEnvironment(serviceName, envCode)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Остановить rollout
     * Останавливает дальнейшее распространение без изменения глобальной версии
     */
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
     * Rollback rollout (FR-52, AR-28)
     */
    @Transactional
    public RolloutResponse rollback(UUID rolloutId, RollbackRolloutRequest request, RequestContext ctx) {
        RolloutEntity rollout = rolloutRepository.findById(rolloutId)
                .orElseThrow(() -> new RolloutNotFoundException(rolloutId));

        ConfigEntity config = rollout.getConfig();
        ensureConfigActive(config);

        // For canary completed rollouts, allow rollback (this is the "undo canary" path)
        boolean isCanaryCompleted = rollout.getType() == RolloutType.CANARY
                && rollout.getStatus() == RolloutStatus.COMPLETED;

        if (!rollout.isActive() && !isCanaryCompleted) {
            throw new RolloutNotActiveException(rolloutId, rollout.getStatus().getValue());
        }

        // Determine which version to rollback to
        long rollbackToVersion;

        if (request != null && request.getTargetVersion() != null) {
            // Explicit target version (only for canary)
            if (!isCanaryCompleted && rollout.getType() != RolloutType.CANARY) {
                throw new IllegalArgumentException("Custom targetVersion is only supported for canary rollouts");
            }

            long requestedVersion = request.getTargetVersion();

            // Validate that this version exists
            configVersionRepository.findByConfigIdAndVersion(config.getId(), requestedVersion)
                    .orElseThrow(() -> new VersionNotFoundException(
                            config.getId(), requestedVersion));

            rollbackToVersion = requestedVersion;
        } else {
            // Default: rollback to baseline
            rollbackToVersion = rollout.getBaselineVersion();
        }

        return executeRollback(rollout, config, rollbackToVersion,
                request != null ? request.getComment() : null, ctx);
    }

    /**
     * Rollback a config to a specific version by creating an instant rollout.
     * This is the replacement for ConfigService.rollback — it publishes to Centrifugo
     * so SDK is notified.
     */
    @Transactional
    public RolloutResponse rollbackConfig(UUID configId, RollbackRequest request,
                                          RequestContext ctx) {
        ConfigEntity config = findActiveConfig(configId);

        if (!Objects.equals(request.getExpectedVersion(), config.getCurrentVersion())) {
            throw new VersionConflictException(
                    request.getExpectedVersion(), config.getCurrentVersion());
        }

        // Check no active rollout
        rolloutRepository.findActiveByConfigId(configId).ifPresent(existing -> {
            throw new ActiveRolloutExistsException(configId, existing.getId());
        });

        long rollbackToVersion = request.getTargetVersion();

        ConfigVersionEntity rollbackVersionEntity = configVersionRepository
                .findByConfigIdAndVersion(configId, rollbackToVersion)
                .orElseThrow(() -> new VersionNotFoundException(configId, rollbackToVersion));

        String rollbackPayload = rollbackVersionEntity.getPayload();
        Object rollbackPayloadObj = deserializePayload(rollbackPayload);

        // Создаем новую версию конфига (FR-24)
        long previousVersion = config.getCurrentVersion();
        long newVersion = previousVersion + 1;

        config.setCurrentVersion(newVersion);
        configRepository.save(config);

        String comment = request != null && request.getComment() != null
                ? request.getComment()
                : "Rollback to version " + rollbackToVersion;

        ConfigVersionEntity newVersionEntity = ConfigVersionEntity.builder()
                .config(config)
                .version(newVersion)
                .payload(rollbackPayload)
                .changeType("rollback")
                .author(ctx.getActor())
                .comment(comment)
                .build();
        configVersionRepository.save(newVersionEntity);

        // Вычисляем diff
        String currentPayload = configVersionRepository
                .findByConfigIdAndVersion(configId, previousVersion)
                .map(ConfigVersionEntity::getPayload)
                .orElse(null);
        DiffResponse diff = diffService.computeDiff(currentPayload, rollbackPayload, previousVersion, newVersion);
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

        // Publish to Centrifugo
        publishInstantUpdate(config, serviceName, envCode, config.getConfigKey(),
                newVersion, rollbackPayloadObj, rollout.getId());

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

        ConfigVersionEntity targetVersionEntity = configVersionRepository
                .findByConfigIdAndVersion(config.getId(), rollout.getTargetVersion())
                .orElseThrow(() -> new IllegalStateException("Target version not found"));

        Object payload = deserializePayload(targetVersionEntity.getPayload());

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
     * Проверяет, есть ли активный rollout для данного конфига
     */
    @Transactional(readOnly = true)
    public boolean hasActiveRollout(UUID configId) {
        return rolloutRepository.findActiveByConfigId(configId).isPresent();
    }

    /**
     * Вызывается scheduler-ом для продвижения всех готовых gradual rollout-ов
     */
    @Transactional
    public void processScheduledDeployments() {
        List<RolloutEntity> ready = rolloutRepository.findReadyForNextDeployment(Instant.now());
        for (RolloutEntity rollout : ready) {
            try {
                ConfigEntity config = rollout.getConfig();

                // Skip if config was deleted
                if (!config.isActive()) {
                    log.warn("Skipping deployment for rollout {} — config {} is deleted", rollout.getId(), config.getId());
                    rollout.markStopped();
                    rolloutRepository.save(rollout);
                    continue;
                }

                String serviceName = config.getService().getName();
                String envCode = config.getEnvironment().getCode();
                String key = config.getConfigKey();

                ConfigVersionEntity targetVersionEntity = configVersionRepository
                        .findByConfigIdAndVersion(config.getId(), rollout.getTargetVersion())
                        .orElse(null);

                if (targetVersionEntity == null) {
                    log.error("Target version {} not found for rollout {}", rollout.getTargetVersion(), rollout.getId());
                    continue;
                }

                Object payload = deserializePayload(targetVersionEntity.getPayload());
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
            } catch (Exception e) {
                log.error("Failed to process deployment for rollout {}", rollout.getId(), e);
            }
        }
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

    private RolloutResponse executeRollback(RolloutEntity rollout, ConfigEntity config,
                                            long rollbackToVersion, String comment,
                                            RequestContext ctx) {
        if (rollbackToVersion == 0) {
            throw new IllegalStateException(
                    "Cannot rollback: target version is 0 (no previous version exists)");
        }

        ConfigVersionEntity rollbackVersionEntity = configVersionRepository
                .findByConfigIdAndVersion(config.getId(), rollbackToVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "Version " + rollbackToVersion + " not found for config "
                                + config.getId()));

        String rollbackPayload = rollbackVersionEntity.getPayload();
        Object rollbackPayloadObj = deserializePayload(rollbackPayload);

        long previousVersion = config.getCurrentVersion();
        long newVersion = previousVersion + 1;
        config.setCurrentVersion(newVersion);
        configRepository.save(config);

        String rollbackComment = comment != null
                ? comment
                : "Rollback from rollout " + rollout.getId() + " to version " + rollbackToVersion;

        ConfigVersionEntity newVersionEntity = ConfigVersionEntity.builder()
                .config(config)
                .version(newVersion)
                .payload(rollbackPayload)
                .changeType("rollback")
                .author(ctx.getActor())
                .comment(rollbackComment)
                .build();
        configVersionRepository.save(newVersionEntity);

        String currentPayload = configVersionRepository
                .findByConfigIdAndVersion(config.getId(), previousVersion)
                .map(ConfigVersionEntity::getPayload)
                .orElse(null);
        DiffResponse diff = diffService.computeDiff(currentPayload, rollbackPayload, previousVersion, newVersion);
        String diffJson = diffService.serializeDiff(diff);

        String serviceName = config.getService().getName();
        String envCode = config.getEnvironment().getCode();

        publishInstantUpdate(config, serviceName, envCode, config.getConfigKey(),
                newVersion, rollbackPayloadObj, rollout.getId());

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

        try {
            centrifugoOutboxRepository.save(outbox);
        } catch (DataIntegrityViolationException e) {
            // Идемпотентность: запись с таким ключом уже существует
            log.debug("Duplicate outbox entry for key: {}", idempotencyKey);
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
