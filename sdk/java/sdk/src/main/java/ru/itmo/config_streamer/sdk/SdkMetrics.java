package ru.itmo.config_streamer.sdk;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Interface for SDK metrics recording.
 * Has two implementations: real Micrometer metrics and no-op.
 */
interface SdkMetrics {
    
    /**
     * Records config delivery time.
     * @param configKey the config key
     * @param messageType the message type (update, gradual_deploy, canary_deploy, etc.)
     * @param deliveryTimeMs delivery time in milliseconds
     */
    void recordDeliveryTime(long deliveryTimeMs);

    /**
     * Increments the message received counter.
     * @param configKey the config key
     * @param messageType the message type
     */
    void incrementMessagesReceived(String messageType);

    /**
     * Increments active connections gauge.
     */
    void incrementActiveConnections();

    /**
     * Decrements active connections gauge.
     */
    void decrementActiveConnections();

    /**
     * Increments connection errors counter.
     */
    void incrementConnectionErrors();

    /**
     * Records token fetch duration.
     * @param tokenType the token type (connection, subscription)
     * @param durationMs duration in milliseconds
     */
    void recordTokenFetchDuration(String tokenType, long durationMs);

    /**
     * Sets the config cache size.
     * @param size number of configs in cache
     */
    void setCacheSize(int size);

    /**
     * Creates a no-op SdkMetrics (metrics disabled).
     * @return NoOpSdkMetrics instance
     */
    static SdkMetrics noOp() {
        return new NoOpSdkMetrics();
    }

    /**
     * Creates a Micrometer-based SdkMetrics.
     * @param registry the Micrometer MeterRegistry
     * @return PrometheusSdkMetrics instance
     */
    static SdkMetrics withRegistry(MeterRegistry registry) {
        return new PrometheusSdkMetrics(registry);
    }
}
