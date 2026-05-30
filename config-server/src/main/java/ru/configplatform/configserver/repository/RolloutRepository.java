package ru.configplatform.configserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.configplatform.configserver.model.RolloutEntity;
import ru.configplatform.configserver.model.RolloutStatus;
import ru.configplatform.configserver.model.RolloutType;

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

    @Query("SELECT r FROM RolloutEntity r " +
            "WHERE r.config.service.name = :serviceName " +
            "AND r.config.environment.code = :envCode " +
            "AND r.type = :type " +
            "AND r.status = :status")
    List<RolloutEntity> findByServiceEnvTypeAndStatus(
            @Param("serviceName") String serviceName,
            @Param("envCode") String envCode,
            @Param("type") RolloutType type,
            @Param("status") RolloutStatus status
    );

    default List<RolloutEntity> findCompletedCanaryByServiceEnv(String serviceName, String envCode) {
        return findByServiceEnvTypeAndStatus(serviceName, envCode, RolloutType.CANARY, RolloutStatus.COMPLETED);
    }

    @Query("SELECT r FROM RolloutEntity r " +
            "WHERE r.config.id = :configId " +
            "AND r.type = :type " +
            "AND r.status = :status")
    List<RolloutEntity> findByConfigIdTypeAndStatus(
            @Param("configId") UUID configId,
            @Param("type") RolloutType type,
            @Param("status") RolloutStatus status
    );

    default Optional<RolloutEntity> findCompletedCanaryByConfigId(UUID configId) {
        List<RolloutEntity> results = findByConfigIdTypeAndStatus(configId, RolloutType.CANARY, RolloutStatus.COMPLETED);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Query("SELECT r FROM RolloutEntity r WHERE r.config.id = :configId " +
            "AND r.type IN ('instant', 'gradual') " +
            "AND r.status = 'completed' " +
            "ORDER BY r.completedAt DESC")
    List<RolloutEntity> findCompletedFullRolloutsByConfigId(@Param("configId") UUID configId);

    long countByStatusIn(java.util.Collection<RolloutStatus> statuses);
}
