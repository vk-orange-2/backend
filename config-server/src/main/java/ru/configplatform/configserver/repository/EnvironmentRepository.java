package ru.configplatform.configserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.configplatform.configserver.model.EnvironmentEntity;

import java.util.Optional;

@Repository
public interface EnvironmentRepository extends JpaRepository<EnvironmentEntity, Short> {

    Optional<EnvironmentEntity> findByCode(String code);
}
