package ru.configplatform.configserver.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.configplatform.configserver.service.RolloutService;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class RolloutScheduler {

    private final RolloutService rolloutService;

    /**
     * Каждые 5 секунд проверяем, есть ли gradual rollout-ы,
     * готовые к следующему deployment-у.
     */
    @Scheduled(fixedDelay = 5000)
    public void processDeployments() {
        try {
            rolloutService.processScheduledDeployments();
        } catch (Exception e) {
            log.error("Error processing scheduled deployments", e);
        }
    }
}
