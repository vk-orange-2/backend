package ru.configplatform.configserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.configplatform.configserver.dto.ConfigStateResponse;
import ru.configplatform.configserver.model.*;
import ru.configplatform.configserver.repository.*;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConfigStateService {

    private static final String ENCRYPTED_MARKER = "encrypted";

    private final ServiceRepository serviceRepository;
    private final EnvironmentRepository environmentRepository;
    private final ConfigRepository configRepository;
    private final ConfigVersionRepository configVersionRepository;
    private final RolloutRepository rolloutRepository;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    /**
     * Full state of all configs for a service+environment.
     *
     * For each config returns:
     * 1. latestVersion — last saved version (may not be rolled out)
     * 2. globalVersion — last version rolled out to ALL instances
     *    (completed instant or completed gradual)
     * 3. gradualRollout — active gradual rollout (in_progress)
     * 4. canary — active canary deployment (completed canary rollout)
     */
    @Observed(
            name = "config.state",
            contextualName = "build-config-state"
    )
    @Transactional(readOnly = true)
    public ConfigStateResponse getServiceEnvState(String serviceName, String envCode) {
        ServiceEntity service = serviceRepository.findByName(serviceName).orElse(null);
        if (service == null) {
            return ConfigStateResponse.builder()
                    .serviceName(serviceName)
                    .environment(envCode)
                    .configs(List.of())
                    .build();
        }

        EnvironmentEntity environment = environmentRepository.findByCode(envCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown environment: " + envCode + ". Valid values: dev, stage, prod"));

        List<ConfigEntity> configs = configRepository
                .findByServiceAndEnvironmentAndStatus(service, environment, "active");

        List<ConfigStateResponse.ConfigStateEntry> entries = configs.stream()
                .map(this::buildConfigStateEntry)
                .toList();

        return ConfigStateResponse.builder()
                .serviceName(serviceName)
                .environment(envCode)
                .configs(entries)
                .build();
    }

    private ConfigStateResponse.ConfigStateEntry buildConfigStateEntry(ConfigEntity config) {
        UUID configId = config.getId();

        // Latest version (in DB, may not be rolled out)
        long latestVersion = config.getCurrentVersion();
        Object latestPayload = loadPayload(configId, latestVersion, config.getIsSecret());

        // Global version: last completed instant or gradual rollout
        Long globalVersion = null;
        Object globalPayload = null;

        List<RolloutEntity> completedFullRollouts =
                rolloutRepository.findCompletedFullRolloutsByConfigId(configId);

        if (!completedFullRollouts.isEmpty()) {
            RolloutEntity lastGlobal = completedFullRollouts.get(0);
            globalVersion = lastGlobal.getTargetVersion();
            globalPayload = loadPayload(configId, globalVersion, config.getIsSecret());
        }

        // Active gradual rollout (in_progress)
        ConfigStateResponse.GradualRolloutState gradualState = null;
        var activeRollout = rolloutRepository.findActiveByConfigId(configId);
        if (activeRollout.isPresent()
                && activeRollout.get().getType() == RolloutType.GRADUAL
                && activeRollout.get().getStatus() == RolloutStatus.IN_PROGRESS) {
            RolloutEntity r = activeRollout.get();
            Object targetPayload = loadPayload(configId, r.getTargetVersion(), config.getIsSecret());
            gradualState = ConfigStateResponse.GradualRolloutState.builder()
                    .rolloutId(r.getId())
                    .targetVersion(r.getTargetVersion())
                    .targetPayload(targetPayload)
                    .totalDeployments(r.getTotalDeployments())
                    .currentDeployment(r.getCurrentDeployment())
                    .deploymentIntervalSeconds(r.getDeploymentIntervalSeconds())
                    .status(r.getStatus().getValue())
                    .build();
        }

        // Completed canary
        ConfigStateResponse.CanaryState canaryState = null;
        var canaryRollout = rolloutRepository.findCompletedCanaryByConfigId(configId);
        if (canaryRollout.isPresent()) {
            RolloutEntity c = canaryRollout.get();
            Object canaryPayload = loadPayload(configId, c.getTargetVersion(), config.getIsSecret());
            canaryState = ConfigStateResponse.CanaryState.builder()
                    .rolloutId(c.getId())
                    .canaryVersion(c.getTargetVersion())
                    .canaryPayload(canaryPayload)
                    .percentage(c.getCanaryPercentage())
                    .status(c.getStatus().getValue())
                    .build();
        }

        return ConfigStateResponse.ConfigStateEntry.builder()
                .configId(configId)
                .configKey(config.getConfigKey())
                .isSecret(config.getIsSecret())
                .latestVersion(latestVersion)
                .latestPayload(latestPayload)
                .globalVersion(globalVersion)
                .globalPayload(globalPayload)
                .gradualRollout(gradualState)
                .canary(canaryState)
                .build();
    }

    private Object loadPayload(UUID configId, long version, boolean isSecret) {
        return configVersionRepository
                .findByConfigIdAndVersion(configId, version)
                .map(v -> {
                    String raw = v.getPayload();
                    if (raw == null) return null;
                    String plain;
                    if (isSecret) {
                        try {
                            JsonNode node = objectMapper.readTree(raw);
                            if (node.has(ENCRYPTED_MARKER) && node.get(ENCRYPTED_MARKER).isTextual()) {
                                plain = encryptionService.decrypt(node.get(ENCRYPTED_MARKER).asText());
                            } else {
                                plain = encryptionService.decrypt(raw);
                            }
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException("Failed to parse encrypted payload wrapper", e);
                        }
                    } else {
                        plain = raw;
                    }
                    return deserializePayload(plain);
                })
                .orElse(null);
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
