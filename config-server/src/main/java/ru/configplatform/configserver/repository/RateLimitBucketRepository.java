package ru.configplatform.configserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.configplatform.configserver.model.RateLimitBucketEntity;

public interface RateLimitBucketRepository
        extends JpaRepository<RateLimitBucketEntity, String> {
}