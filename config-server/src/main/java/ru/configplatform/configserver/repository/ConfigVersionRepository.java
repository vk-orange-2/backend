package ru.configplatform.configserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.configplatform.configserver.model.ConfigVersionEntity;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConfigVersionRepository extends JpaRepository<ConfigVersionEntity, UUID> {

    Optional<ConfigVersionEntity> findByConfigIdAndVersion(UUID configId, Long version);
}
