package ru.configplatform.configserver.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rollouts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RolloutEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "config_id", nullable = false)
    private ConfigEntity config;

    @Column(nullable = false)
    @Convert(converter = RolloutTypeConverter.class)
    private RolloutType type;

    @Column(nullable = false)
    @Convert(converter = RolloutStatusConverter.class)
    @Builder.Default
    private RolloutStatus status = RolloutStatus.PENDING;

    @Column(name = "baseline_version", nullable = false)
    private Long baselineVersion;

    @Column(name = "target_version", nullable = false)
    private Long targetVersion;

    @Column(name = "total_deployments", nullable = false)
    @Builder.Default
    private Integer totalDeployments = 1;

    @Column(name = "current_deployment", nullable = false)
    @Builder.Default
    private Integer currentDeployment = 0;

    @Column(name = "deployment_interval_seconds", nullable = false)
    @Builder.Default
    private Integer deploymentIntervalSeconds = 0;

    @Column(name = "next_deployment_at")
    private Instant nextDeploymentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "rolled_back_at")
    private Instant rolledBackAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = Instant.now();
    }

    public boolean isActive() {
        return status.isActive();
    }

    public void markInProgress() {
        this.status = RolloutStatus.IN_PROGRESS;
        this.startedAt = Instant.now();
    }

    public void markCompleted() {
        this.status = RolloutStatus.COMPLETED;
        this.completedAt = Instant.now();
        this.nextDeploymentAt = null;
    }

    public void markStopped() {
        this.status = RolloutStatus.STOPPED;
        this.stoppedAt = Instant.now();
        this.nextDeploymentAt = null;
    }

    public void markRolledBack() {
        this.status = RolloutStatus.ROLLED_BACK;
        this.rolledBackAt = Instant.now();
        this.nextDeploymentAt = null;
    }

    public void advanceDeployment() {
        this.currentDeployment++;
        if (this.currentDeployment >= this.totalDeployments) {
            markCompleted();
        } else {
            this.nextDeploymentAt = Instant.now()
                    .plusSeconds(this.deploymentIntervalSeconds);
        }
    }
}
