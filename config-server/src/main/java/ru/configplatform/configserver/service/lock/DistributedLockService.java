package ru.configplatform.configserver.service.lock;

public interface DistributedLockService {

    void acquireTransactionLock(String lockKey);
}
