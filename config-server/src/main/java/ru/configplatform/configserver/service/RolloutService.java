package ru.configplatform.configserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.configplatform.configserver.dto.CreateRolloutRequest;
import ru.configplatform.configserver.dto.DiffResponse;
import ru.configplatform.configserver.dto.RequestContext;
import ru.configplatform.configserver.dto.RolloutResponse;
import ru.configplatform.configserver.exception.ActiveRolloutExistsException;
import ru.configplatform.configserver.exception.CanaryPolicyViolationException;
import ru.configplatform.configserver.exception.ConfigNotFoundException;
import ru.configplatform.configserver.exception.RolloutNotActiveException;
import ru.configplatform.configserver.exception.RolloutNotFoundException;
import ru.configplatform.configserver.model.*;
import ru.configplatform.configserver.repository.CentrifugoOutboxRepository;
import ru.configplatform.configserver.repository.ConfigRepository;
import ru.configplatform.configserver.repository.ConfigVersionRepository;
import ru.configplatform.configserver.repository.RolloutRepository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RolloutService {

    private final RolloutRepository rolloutRepository;
    private final ConfigRepository configRepository;
    private final ConfigVersionRepository configVersionRepository;
    private final CentrifugoOutboxRepository centrifugoOutboxRepository;
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
        ConfigEntity config = configRepository.findByIdAndStatus(request.getConfigId(), "active")
                .orElseThrow(() -> new ConfigNotFoundException(request.getConfigId()));

        String serviceName = config.getService().getName();
        String envCode = config.getEnvironment().getCode();

        // ──────────────────────────────────────────────────────────
        // Advisory lock: serializes all rollout creation
        // for the same service+environment.
        // Released automatically when transaction commits/rolls back.
        // ──────────────────────────────────────────────────────────
        String lockKey = "rollout:" + serviceName + ":" + envCode;
        rolloutRepository.acquireServiceEnvLock(lockKey);

        // Проверяем, нет ли уже активного rollout
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

//                TODO: а нужно ли
                // Публикуем уведомление о начале gradual rollout
                publishGradualStart(config, serviceName, envCode, key, targetVersion, totalDeployments, rollout.getId());

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
        return toResponse(rollout);
    }

    /**
     * Список rollout-ов для конфига
     */
    @Transactional(readOnly = true)
    public List<RolloutResponse> getByConfigId(UUID configId) {
        return rolloutRepository.findByConfigIdOrderByCreatedAtDesc(configId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Получить активный rollout для конфига (для SDK при реконнекте)
     */
    @Transactional(readOnly = true)
    public RolloutResponse getActiveByConfigId(UUID configId) {
        configRepository.findById(configId)
                .orElseThrow(() -> new ConfigNotFoundException(configId));

        return rolloutRepository.findActiveByConfigId(configId)
                .map(this::toResponse)
                .orElse(null);
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

//    TODO: обсудили, что для canary хотим уметь руками задавать, на какую версию откатываться (либо дефолтную)
    /**
     * Rollback rollout (FR-52, AR-28)
     */
    @Transactional
    public RolloutResponse rollback(UUID rolloutId, RequestContext ctx) {
        RolloutEntity rollout = rolloutRepository.findById(rolloutId)
                .orElseThrow(() -> new RolloutNotFoundException(rolloutId));

        // For canary completed rollouts, allow rollback (this is the "undo canary" path)
        boolean isCanaryCompleted = rollout.getType() == RolloutType.CANARY
                && rollout.getStatus() == RolloutStatus.COMPLETED;

        if (!rollout.isActive() && !isCanaryCompleted) {
            throw new RolloutNotActiveException(rolloutId, rollout.getStatus().getValue());
        }

        ConfigEntity config = rollout.getConfig();
        long baselineVersion = rollout.getBaselineVersion();

        if (baselineVersion == 0) {
            throw new IllegalStateException("Cannot rollback rollout: baseline version is 0 (first config version)");
        }

        // Получаем payload baseline версии
        ConfigVersionEntity baselineVersionEntity = configVersionRepository
                .findByConfigIdAndVersion(config.getId(), baselineVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "Baseline version " + baselineVersion + " not found for config " + config.getId()));

        String baselinePayload = baselineVersionEntity.getPayload();
        Object baselinePayloadObj = deserializePayload(baselinePayload);

        // Создаем новую версию конфига (FR-24)
        long previousVersion = config.getCurrentVersion();
        long newVersion = previousVersion + 1;

        config.setCurrentVersion(newVersion);
        configRepository.save(config);

        ConfigVersionEntity newVersionEntity = ConfigVersionEntity.builder()
                .config(config)
                .version(newVersion)
                .payload(baselinePayload)
                .changeType("rollback")
                .author(ctx.getActor())
                .comment("Rollback from rollout " + rolloutId + " to baseline version " + baselineVersion)
                .build();
        configVersionRepository.save(newVersionEntity);

        // Вычисляем diff
        String currentPayload = configVersionRepository
                .findByConfigIdAndVersion(config.getId(), previousVersion)
                .map(ConfigVersionEntity::getPayload)
                .orElse(null);
        DiffResponse diff = diffService.computeDiff(currentPayload, baselinePayload, previousVersion, newVersion);
        String diffJson = diffService.serializeDiff(diff);

        // Публикуем в основной канал (всем клиентам)
        String serviceName = config.getService().getName();
        String envCode = config.getEnvironment().getCode();
        String key = config.getConfigKey();

        if (isCanaryCompleted) {
            // TODO: что такое canary rollback и почему решили не делать publishInstantUpdate?
            // Publish canary_rollback to the main channel
            publishCanaryRollback(config, serviceName, envCode, key, newVersion,
                    baselinePayloadObj, rollout.getId());
        } else {
            // Standard rollback — publish to main channel
            publishInstantUpdate(config, serviceName, envCode, key, newVersion,
                    baselinePayloadObj, rollout.getId());
        }

        // Переводим rollout в rolled_back
        rollout.markRolledBack();
        rolloutRepository.save(rollout);

        // Аудит: ROLLBACK (стандартный)
        auditService.log(config, "ROLLBACK", previousVersion, newVersion, diffJson, ctx);

        Map<String, Object> auditData = new LinkedHashMap<>();
        auditData.put("rolloutId", rollout.getId().toString());
        auditData.put("baselineVersion", baselineVersion);
        auditData.put("newVersion", newVersion);
        if (isCanaryCompleted) {
            auditData.put("canaryRollback", true);
        }

        auditService.log(config, "ROLLOUT_ROLLBACK", rollout.getBaselineVersion(),
                newVersion, serializePayload(auditData), ctx);

        return toResponse(rollout);
    }

    /**
     * Выполнить следующий deployment для gradual rollout (вызывается scheduler-ом или вручную)
     */
    @Transactional
    public RolloutResponse deployNext(UUID rolloutId, RequestContext ctx) {
        RolloutEntity rollout = rolloutRepository.findById(rolloutId)
                .orElseThrow(() -> new RolloutNotFoundException(rolloutId));

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
     * Возвращает активный rollout entity (если есть) для использования в блокировке
     */
    @Transactional(readOnly = true)
    public RolloutEntity findActiveRolloutEntity(UUID configId) {
        return rolloutRepository.findActiveByConfigId(configId).orElse(null);
    }

    /**
     * Find completed canary rollout for a config (if any)
     */
    @Transactional(readOnly = true)
    public RolloutEntity findCompletedCanaryForConfig(UUID configId) {
        return rolloutRepository.findCompletedCanaryByConfigId(configId).orElse(null);
    }

    /**
     * Find all completed canary rollouts for a service+env
     */
    @Transactional(readOnly = true)
    public List<RolloutEntity> findCompletedCanariesForServiceEnv(String serviceName, String envCode) {
        return rolloutRepository.findCompletedCanaryByServiceEnv(serviceName, envCode);
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

    private void executeNextDeployment(RolloutEntity rollout, ConfigEntity config,
                                       String serviceName, String envCode, String key,
                                       long targetVersion, Object payload) {
        rollout.advanceDeployment();
        int deploymentNumber = rollout.getCurrentDeployment();

        //    TODO: выяснили, что проблематично использовать отдельные каналы, лучше использовать один основной и в него уже все публиковать
        // Публикуем в канал deployment-а:
        // service:<service_name>:<env_name>:<key>:<deployment_number>
        String channel = String.format("service:%s:%s:%s:%d", serviceName, envCode, key, deploymentNumber);
        String idempotencyKey = String.format("rollout:%s:deploy:%d", rollout.getId(), deploymentNumber);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "gradual_deploy");
        data.put("configId", config.getId().toString());
        data.put("key", key);
        data.put("version", targetVersion);
        data.put("deployment", deploymentNumber);
        data.put("totalDeployments", rollout.getTotalDeployments());
        data.put("payload", payload);
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

    private void publishCanaryRollback(ConfigEntity config, String serviceName,
                                       String envCode, String key, long version,
                                       Object payload, UUID rolloutId) {
        String channel = String.format("service:%s:%s", serviceName, envCode);
        String idempotencyKey = String.format("canary-rb:%s:v%d", config.getId(), version);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "canary_rollback");
        data.put("configId", config.getId().toString());
        data.put("key", key);
        data.put("version", version);
        data.put("payload", payload);
        data.put("rolloutId", rolloutId.toString());
        data.put("timestamp", Instant.now().toString());

        publishToCentrifugo(channel, data, idempotencyKey);
    }

//    TODO: а нужно ли это, при условии, что хотим переехать на единый канал
    private void publishGradualStart(ConfigEntity config, String serviceName,
                                     String envCode, String key, long version,
                                     int totalDeployments, UUID rolloutId) {
        String channel = String.format("service:%s:%s", serviceName, envCode);
        String idempotencyKey = String.format("rollout:%s:start", rolloutId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "gradual_start");
        data.put("configId", config.getId().toString());
        data.put("key", key);
        data.put("version", version);
        data.put("deployments", totalDeployments);
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
