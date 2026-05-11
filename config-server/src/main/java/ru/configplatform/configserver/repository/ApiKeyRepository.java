package ru.configplatform.configserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import ru.configplatform.configserver.model.ApiKeyEntity;
import ru.configplatform.configserver.model.ApiKeyId;

import java.util.Optional;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, ApiKeyId> {
    Optional<ApiKeyEntity> findByValue(String value);
}
