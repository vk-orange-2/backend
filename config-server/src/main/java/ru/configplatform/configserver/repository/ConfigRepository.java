package ru.configplatform.configserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.configplatform.configserver.model.ConfigEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConfigRepository extends JpaRepository<ConfigEntity, UUID> {

    List<ConfigEntity> findByServiceAndEnv(String service, String env);

    Optional<ConfigEntity> findByServiceAndEnvAndKey(String service, String env, String key);

    @Query("SELECT DISTINCT c.service FROM ConfigEntity c ORDER BY c.service")
    List<String> findDistinctServices();
}
