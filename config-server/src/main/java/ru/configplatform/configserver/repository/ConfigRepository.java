package ru.configplatform.configserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.configplatform.configserver.model.ConfigEntity;
import ru.configplatform.configserver.model.EnvironmentEntity;
import ru.configplatform.configserver.model.ServiceEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConfigRepository extends JpaRepository<ConfigEntity, UUID> {

    List<ConfigEntity> findByServiceAndEnvironmentAndStatus(
            ServiceEntity service,
            EnvironmentEntity environment,
            String status
    );

    Optional<ConfigEntity> findByServiceAndEnvironmentAndConfigKey(
            ServiceEntity service,
            EnvironmentEntity environment,
            String configKey
    );

    Optional<ConfigEntity> findByIdAndStatus(UUID id, String status);
}
