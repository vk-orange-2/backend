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
     */
    @Transactional
    public RolloutResponse createAndStart(CreateRolloutRequest request, RequestContext ctx) {
        ConfigEntity config = configRepository.findByIdAndStatus(request.getConfigId(), "active")
                .orElseThrow(() -> new ConfigNotFoundException(request.getConfigId()));

        // Проверяем, нет ли уже активного rollout
        rolloutRepository.findActiveByConfigId(config.getId()).ifPresent(existing -> {
            throw new ActiveRolloutExistsException(config.getId(), existing.getId());
        });

        RolloutType type = RolloutType.fromValue(request.getType());

        // Для rollout нам нужна текущая версия как target (последняя опубликованная)
        // baseline — это то, к чему мы откатимся при rollback
        // В нашей модели: при создании rollout конфиг уже обновлён (новая версия создана),
        // и rollout отвечает за ДОСТАВКУ этой версии клиентам.
        // Поэтому baseline = version - 1 (предыдущая), target = currentVersion
        //
        // Но если baseline_version == 0, значит это первый конфиг — rollback невозможен,
        // но rollout всё равно можно создать.

        long targetVersion = config.getCurrentVersion();
        long baseline = targetVersion > 1 ? targetVersion - 1 : 0;

        String serviceName = config.getService().getName();
        String envCode = config.getEnvironment().getCode();
        String key = config.getConfigKey();

        ConfigVersionEntity targetVersionEntity = configVersionRepository
                .findByConfigIdAndVersion(config.getId(), targetVersion)
                .orElseThrow(() -> new IllegalStateException("Target version not found"));

        Object payload = deserializePayload(targetVersionEntity.getPayload());

        RolloutEntity rollout;

        if (type == RolloutType.INSTANT) {
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

        } else {
            // gradual
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

            // Публикуем уведомление о начале gradual rollout в основной канал
            publishGradualStart(config, serviceName, envCode, key, targetVersion, totalDeployments, rollout.getId());

            // Сразу выполняем первый deployment
            executeNextDeployment(rollout, config, serviceName, envCode, key, targetVersion, payload);
        }

        // Аудит
        auditService.log(config, "ROLLOUT_START", baseline, targetVersion,
                serializePayload(Map.of(
                        "rolloutId", rollout.getId().toString(),
                        "type", rollout.getType(),
                        "totalDeployments", rollout.getTotalDeployments()
                )), ctx);

        return toResponse(rollout);
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

    /**
     * Rollback rollout (FR-52, AR-28)
     *
     * 1. Определяет baseline version rollout
     * 2. Выполняет глобальный rollback конфига к baseline (создавая новую версию)
     * 3. Публикует новую версию всем через основной канал
     * 4. Переводит rollout в rolled_back
     */
    @Transactional
    public RolloutResponse rollback(UUID rolloutId, RequestContext ctx) {
        RolloutEntity rollout = rolloutRepository.findById(rolloutId)
                .orElseThrow(() -> new RolloutNotFoundException(rolloutId));

        if (!rollout.isActive()) {
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

        publishInstantUpdate(config, serviceName, envCode, key, newVersion, baselinePayloadObj, rollout.getId());

        // Переводим rollout в rolled_back
        rollout.markRolledBack();
        rolloutRepository.save(rollout);

        // Аудит: ROLLBACK (стандартный)
        auditService.log(config, "ROLLBACK", previousVersion, newVersion, diffJson, ctx);

        // Аудит: ROLLOUT_ROLLBACK
        auditService.log(config, "ROLLOUT_ROLLBACK", rollout.getBaselineVersion(),
                newVersion,
                serializePayload(Map.of(
                        "rolloutId", rollout.getId().toString(),
                        "baselineVersion", baselineVersion,
                        "newVersion", newVersion
                )), ctx);

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
     * Используется ConfigService для блокировки обновлений
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
