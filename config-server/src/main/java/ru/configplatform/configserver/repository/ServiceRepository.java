package ru.configplatform.configserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.configplatform.configserver.model.ServiceEntity;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServiceRepository extends JpaRepository<ServiceEntity, UUID> {

    Optional<ServiceEntity> findByName(String name);
}
