package ru.configplatform.configserver.service.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory lock for tests (H2 doesn't support pg_advisory_xact_lock)
 */
@Service
@Profile("test")
@Slf4j
public class InMemoryDistributedLockService implements DistributedLockService {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public void acquireTransactionLock(String lockKey) {
        log.debug("Acquiring in-memory lock for key: {}", lockKey);
        // For single-threaded tests, this is essentially a no-op
        // but maintains the contract
        locks.computeIfAbsent(lockKey, k -> new ReentrantLock());
    }
}
