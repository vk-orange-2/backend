package ru.configplatform.configserver.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "api_keys")
@IdClass(ApiKeyId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKeyEntity {
    @Id
    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Id
    @Column(name = "environment_id", nullable = false, updatable = false)
    private Short environmentId;

    @Column(name = "encrypted_key", nullable = false)
    private String value;
}
