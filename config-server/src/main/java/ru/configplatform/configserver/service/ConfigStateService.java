package ru.configplatform.configserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.configplatform.configserver.dto.ConfigStateResponse;
import ru.configplatform.configserver.model.ConfigEntity;
import ru.configplatform.configserver.model.ConfigVersionEntity;
import ru.configplatform.configserver.model.EnvironmentEntity;
import ru.configplatform.configserver.model.RolloutEntity;
import ru.configplatform.configserver.model.RolloutStatus;
import ru.configplatform.configserver.model.RolloutType;
import ru.configplatform.configserver.model.ServiceEntity;
import ru.configplatform.configserver.repository.ConfigRepository;
import ru.configplatform.configserver.repository.ConfigVersionRepository;
import ru.configplatform.configserver.repository.EnvironmentRepository;
import ru.configplatform.configserver.repository.RolloutRepository;
import ru.configplatform.configserver.repository.ServiceRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ConfigStateService {

    private final ServiceRepository serviceRepository;
    private final EnvironmentRepository environmentRepository;
    private final ConfigRepository configRepository;
    private final ConfigVersionRepository configVersionRepository;
    private final RolloutRepository rolloutRepository;
    private final ObjectMapper objectMapper;

    /**
     * Full state of all configs for a service+environment.
     *
     * For each config returns:
     * 1. Global version (current version of config)
     * 2. Gradual rollout state (if active)
     * 3. Canary version (if completed canary exists)
     */
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
        //        TODO: пока сделала последнюю сохраненную, но на самом деле это должна быть версия последнего instant
//         или завершенного gradual, при этом версия должна быть не не rolled back нутая
//         (по идее допом это не нужно проверять, потому что rolled back создает новую версию)
        long globalVersion = config.getCurrentVersion();
        Object globalPayload = loadPayload(config.getId(), globalVersion);

        // Active gradual rollout
        ConfigStateResponse.GradualRolloutState gradualState = null;
        var activeRollout = rolloutRepository.findActiveByConfigId(config.getId());
        if (activeRollout.isPresent() && activeRollout.get().getType() == RolloutType.GRADUAL) {
            RolloutEntity r = activeRollout.get();
            Object targetPayload = loadPayload(config.getId(), r.getTargetVersion());

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
        var canaryRollout = rolloutRepository.findCompletedCanaryByConfigId(config.getId());
        if (canaryRollout.isPresent()) {
            RolloutEntity c = canaryRollout.get();
            Object canaryPayload = loadPayload(config.getId(), c.getTargetVersion());

            canaryState = ConfigStateResponse.CanaryState.builder()
                    .rolloutId(c.getId())
                    .canaryVersion(c.getTargetVersion())
                    .canaryPayload(canaryPayload)
                    .percentage(c.getCanaryPercentage())
                    .status(c.getStatus().getValue())
                    .build();
        }

        return ConfigStateResponse.ConfigStateEntry.builder()
                .configId(config.getId())
                .configKey(config.getConfigKey())
                .isSecret(config.getIsSecret())
                .globalVersion(globalVersion)
                .globalPayload(globalPayload)
                .gradualRollout(gradualState)
                .canary(canaryState)
                .build();
    }

    private Object loadPayload(java.util.UUID configId, long version) {
        return configVersionRepository
                .findByConfigIdAndVersion(configId, version)
                .map(v -> deserializePayload(v.getPayload()))
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