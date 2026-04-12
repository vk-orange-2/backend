package ru.configplatform.configserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.configplatform.configserver.dto.ConfigResponse;
import ru.configplatform.configserver.dto.CreateConfigRequest;
import ru.configplatform.configserver.model.ConfigEntity;
import ru.configplatform.configserver.repository.ConfigRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ConfigService {

    private final ConfigRepository configRepository;

    /**
     * Создает новый конфиг или обновляет существующий (upsert по service+env+key)
     * При обновлении version инкрементируется
     */
    @Transactional
    public ConfigResponse createOrUpdate(CreateConfigRequest request) {
        ConfigEntity entity = configRepository
                .findByServiceAndEnvAndKey(request.getService(), request.getEnv(), request.getKey())
                .map(existing -> {
                    existing.setValue(request.getValue());
                    existing.setVersion(existing.getVersion() + 1);
                    return existing;
                })
                .orElseGet(() -> ConfigEntity.builder()
                        .service(request.getService())
                        .env(request.getEnv())
                        .key(request.getKey())
                        .value(request.getValue())
                        .version(1L)
                        .build());

        ConfigEntity saved = configRepository.save(entity);
        return toResponse(saved);
    }

    /**
     * Возвращает конфиги по service + env
     */
    @Transactional(readOnly = true)
    public List<ConfigResponse> getConfigs(String serviceName, String env) {
        return configRepository.findByServiceAndEnv(serviceName, env)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Возвращает все service
     */
    @Transactional(readOnly = true)
    public List<String> getServices() {
        return configRepository.findDistinctServices();
    }

    private ConfigResponse toResponse(ConfigEntity entity) {
        return ConfigResponse.builder()
                .id(entity.getId())
                .service(entity.getService())
                .env(entity.getEnv())
                .key(entity.getKey())
                .value(entity.getValue())
                .version(entity.getVersion())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
