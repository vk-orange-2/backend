package ru.configplatform.configserver.service.lock;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
@Slf4j
public class PostgresDistributedLockService implements DistributedLockService {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void acquireTransactionLock(String lockKey) {
        log.debug("Acquiring advisory lock for key: {}", lockKey);
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:key))")
                .setParameter("key", lockKey)
                .getSingleResult();
    }
}
