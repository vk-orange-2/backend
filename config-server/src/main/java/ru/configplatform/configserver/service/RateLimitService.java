package ru.configplatform.configserver.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.configplatform.configserver.exception.RateLimitExceededException;
import ru.configplatform.configserver.model.RateLimitBucketEntity;
import ru.configplatform.configserver.repository.RateLimitBucketRepository;
import ru.configplatform.configserver.service.lock.DistributedLockService;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RateLimitBucketRepository repository;
    private final DistributedLockService lockService;

    @Transactional
    public void consume(
            String bucketKey,
            double rate,
            double burst
    ) {

        log.info(
                "consume bucketKey={}, rate={}, burst={}",
                bucketKey,
                rate,
                burst
        );

        lockService.acquireTransactionLock(
                "rate-limit:" + bucketKey
        );

        Instant now = Instant.now();

        RateLimitBucketEntity bucket =
                repository.findById(bucketKey)
                        .orElse(null);

        if (bucket == null) {

            log.info(
                    "NEW bucket={}, initialTokens={}",
                    bucketKey,
                    burst
            );

            bucket = RateLimitBucketEntity.builder()
                    .bucketKey(bucketKey)
                    .tokens(burst)
                    .lastRefillAt(now)
                    .build();
        } else {

            long elapsedMillis =
                    Duration.between(
                            bucket.getLastRefillAt(),
                            now
                    ).toMillis();

            double refill =
                    (elapsedMillis / 1000.0d) * rate;

            double oldTokens =
                    bucket.getTokens();

            bucket.setTokens(
                    Math.min(
                            burst,
                            bucket.getTokens() + refill
                    )
            );

            bucket.setLastRefillAt(now);

            log.info(
                    "EXISTING bucket={}, elapsed={}ms, refill={}, tokensBefore={}, tokensAfter={}",
                    bucketKey,
                    elapsedMillis,
                    refill,
                    oldTokens,
                    bucket.getTokens()
            );
        }

        if (bucket.getTokens() < 1.0d) {

            repository.save(bucket);

            long retryAfter;

            if (rate <= 0) {

                retryAfter = Long.MAX_VALUE;

            } else {

                retryAfter =
                        (long) Math.ceil(
                                (1.0d - bucket.getTokens()) / rate
                        );
            }

            log.warn(
                    "REJECTED bucket={}, tokens={}, retryAfter={}",
                    bucketKey,
                    bucket.getTokens(),
                    retryAfter
            );

            throw new RateLimitExceededException(
                    Math.max(1, retryAfter)
            );
        }

        double beforeConsume =
                bucket.getTokens();

        bucket.setTokens(
                bucket.getTokens() - 1.0d
        );

        repository.save(bucket);

        log.info(
                "ALLOWED bucket={}, tokensBeforeConsume={}, tokensAfterConsume={}",
                bucketKey,
                beforeConsume,
                bucket.getTokens()
        );
    }
}
