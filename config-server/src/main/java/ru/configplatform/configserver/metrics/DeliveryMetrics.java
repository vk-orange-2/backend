package ru.configplatform.configserver.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.configplatform.configserver.model.RolloutStatus;
import ru.configplatform.configserver.repository.CentrifugoOutboxRepository;
import ru.configplatform.configserver.repository.RolloutRepository;

import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
public class DeliveryMetrics {

    private final MeterRegistry meterRegistry;
    private final CentrifugoOutboxRepository outboxRepository;
    private final RolloutRepository rolloutRepository;

    private final AtomicLong outboxBacklog = new AtomicLong(0);
    private final AtomicLong activeRollouts = new AtomicLong(0);

    private Counter enqueueSuccess;
    private Counter enqueueDuplicate;
    private Counter enqueueFailure;
    private Timer enqueueTimer;

    @PostConstruct
    void init() {
        enqueueSuccess = Counter.builder("config_delivery_outbox_enqueue_total")
                .description("Outbox insert attempts that finished successfully")
                .tag("result", "success")
                .register(meterRegistry);

        enqueueDuplicate = Counter.builder("config_delivery_outbox_enqueue_total")
                .description("Outbox insert attempts skipped because of idempotency")
                .tag("result", "duplicate")
                .register(meterRegistry);

        enqueueFailure = Counter.builder("config_delivery_outbox_enqueue_total")
                .description("Outbox insert attempts that failed")
                .tag("result", "failure")
                .register(meterRegistry);

        enqueueTimer = Timer.builder("config_delivery_outbox_enqueue_seconds")
                .description("Time to write delivery event into transactional outbox")
                .publishPercentileHistogram()
                .register(meterRegistry);

        Gauge.builder("config_delivery_outbox_backlog", outboxBacklog, AtomicLong::get)
                .description("Current number of events in outbox")
                .register(meterRegistry);

        Gauge.builder("config_delivery_active_rollouts", activeRollouts, AtomicLong::get)
                .description("Current number of active rollouts")
                .register(meterRegistry);
    }

    public Timer.Sample startEnqueueTimer() {
        return Timer.start(meterRegistry);
    }

    public void markEnqueueSuccess(Timer.Sample sample) {
        sample.stop(enqueueTimer);
        enqueueSuccess.increment();
    }

    public void markDuplicate(Timer.Sample sample) {
        sample.stop(enqueueTimer);
        enqueueDuplicate.increment();
    }

    public void markFailure(Timer.Sample sample) {
        sample.stop(enqueueTimer);
        enqueueFailure.increment();
    }

    @Scheduled(fixedDelayString = "${metrics.refresh-ms:10000}")
    public void refreshDerivedGauges() {
        outboxBacklog.set(outboxRepository.count());
        activeRollouts.set(rolloutRepository.countByStatusIn(
                java.util.List.of(RolloutStatus.PENDING, RolloutStatus.IN_PROGRESS)
        ));
    }
}
