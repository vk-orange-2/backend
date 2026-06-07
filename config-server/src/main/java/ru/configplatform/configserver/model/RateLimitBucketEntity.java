package ru.configplatform.configserver.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "rate_limit_bucket")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitBucketEntity {

    @Id
    @Column(name = "bucket_key")
    private String bucketKey;

    @Column(name = "tokens", nullable = false)
    private double tokens;

    @Column(name = "last_refill_at", nullable = false)
    private Instant lastRefillAt;
}
