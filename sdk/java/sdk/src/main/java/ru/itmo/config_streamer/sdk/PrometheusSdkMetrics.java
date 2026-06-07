package ru.itmo.config_streamer.sdk;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer-based implementation of SdkMetrics.
 * Records metrics to Micrometer MeterRegistry, which integrates with Spring Boot Actuator.
 */
class PrometheusSdkMetrics implements SdkMetrics {
    
    private final MeterRegistry registry;
    private final Counter connectionErrorsCounter;
    private final Timer deliveryTimeTimer;
    private final Timer tokenFetchTimer;
    
    // Use AtomicLong for gauge backing
    private final java.util.concurrent.atomic.AtomicLong activeConnections = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong cacheSize = new java.util.concurrent.atomic.AtomicLong(0);

    PrometheusSdkMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Connection errors counter
        this.connectionErrorsCounter = Counter.builder("sdk_connection_errors_total")
                .description("Total number of connection errors")
                .register(registry);

        // Delivery time timer with histogram buckets for Prometheus
        // publishPercentileHistogram() creates _bucket metrics for histogram_quantile()
        this.deliveryTimeTimer = Timer.builder("sdk_config_delivery_time_seconds")
                .description("Time from server publishing config to SDK receiving it")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(registry);

        // Token fetch timer with histogram buckets
        this.tokenFetchTimer = Timer.builder("sdk_token_fetch_duration_seconds")
                .description("Time to fetch JWT tokens from config server")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(registry);

        // Active connections gauge
        Gauge.builder("sdk_active_connections", activeConnections, java.util.concurrent.atomic.AtomicLong::get)
                .description("Number of active SDK connections")
                .register(registry);

        // Cache size gauge
        Gauge.builder("sdk_config_cache_size", cacheSize, java.util.concurrent.atomic.AtomicLong::get)
                .description("Number of configs in local cache")
                .register(registry);
    }

    @Override
    public void recordDeliveryTime(long deliveryTimeMs) {
        deliveryTimeTimer.record(deliveryTimeMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void incrementMessagesReceived(String messageType) {
        Counter.builder("sdk_messages_received_total")
                .description("Total number of messages received from Centrifugo")
                .tag("message_type", messageType)
                .register(registry)
                .increment();
    }

    @Override
    public void incrementActiveConnections() {
        activeConnections.incrementAndGet();
    }

    @Override
    public void decrementActiveConnections() {
        activeConnections.decrementAndGet();
    }

    @Override
    public void incrementConnectionErrors() {
        connectionErrorsCounter.increment();
    }

    @Override
    public void recordTokenFetchDuration(String tokenType, long durationMs) {
        // For token type differentiation, we use tags but need a cached timer approach
        // Since tokenType varies (connection, subscription), we use the base timer
        // If you need per-type metrics, we can add separate timers
        tokenFetchTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void setCacheSize(int size) {
        cacheSize.set(size);
    }
}
