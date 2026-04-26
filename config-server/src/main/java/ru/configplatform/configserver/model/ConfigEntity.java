package ru.configplatform.configserver.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    private ServiceEntity service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id", nullable = false)
    private EnvironmentEntity environment;

    @Column(name = "config_key", nullable = false)
    private String configKey;

    @Column(name = "current_version", nullable = false)
    @Builder.Default
    private Long currentVersion = 0L;

    @Column(name = "is_secret", nullable = false)
    @Builder.Default
    private Boolean isSecret = false;

    @Column(nullable = false)
    @Builder.Default
    private String status = "active";

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public boolean isActive() {
        return "active".equals(this.status);
    }

    public void markDeleted() {
        this.status = "deleted";
        this.deletedAt = Instant.now();
    }
}
