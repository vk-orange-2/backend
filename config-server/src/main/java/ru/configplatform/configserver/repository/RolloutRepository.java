package ru.configplatform.configserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.configplatform.configserver.model.RolloutEntity;
import ru.configplatform.configserver.model.RolloutStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RolloutRepository extends JpaRepository<RolloutEntity, UUID> {

    @Query("SELECT r FROM RolloutEntity r WHERE r.config.id = :configId AND r.status IN (:statuses)")
    Optional<RolloutEntity> findActiveByConfigId(
            @Param("configId") UUID configId,
            @Param("statuses") List<RolloutStatus> statuses
    );

    default Optional<RolloutEntity> findActiveByConfigId(UUID configId) {
        return findActiveByConfigId(configId,
                List.of(RolloutStatus.PENDING, RolloutStatus.IN_PROGRESS));
    }

    List<RolloutEntity> findByConfigIdOrderByCreatedAtDesc(UUID configId);

    @Query("SELECT r FROM RolloutEntity r WHERE r.status = :status " +
            "AND r.nextDeploymentAt IS NOT NULL AND r.nextDeploymentAt <= :now")
    List<RolloutEntity> findReadyForNextDeployment(
            @Param("status") RolloutStatus status,
            @Param("now") Instant now
    );

    default List<RolloutEntity> findReadyForNextDeployment(Instant now) {
        return findReadyForNextDeployment(RolloutStatus.IN_PROGRESS, now);
    }

    List<RolloutEntity> findByStatusOrderByCreatedAtDesc(RolloutStatus status);

    @Query("SELECT r FROM RolloutEntity r " +
            "WHERE r.config.service.name = :serviceName " +
            "AND r.config.environment.code = :envCode " +
            "AND r.status IN (:statuses)")
    List<RolloutEntity> findActiveByServiceAndEnvironment(
            @Param("serviceName") String serviceName,
            @Param("envCode") String envCode,
            @Param("statuses") List<RolloutStatus> statuses
    );

    default List<RolloutEntity> findActiveByServiceAndEnvironment(String serviceName, String envCode) {
        return findActiveByServiceAndEnvironment(serviceName, envCode,
                List.of(RolloutStatus.PENDING, RolloutStatus.IN_PROGRESS));
    }
}
