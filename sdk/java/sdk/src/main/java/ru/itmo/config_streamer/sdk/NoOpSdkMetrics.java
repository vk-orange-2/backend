package ru.itmo.config_streamer.sdk;

/**
 * No-op implementation of SdkMetrics.
 * All methods do nothing - used when metrics are disabled.
 */
class NoOpSdkMetrics implements SdkMetrics {

    @Override
    public void recordDeliveryTime(long deliveryTimeMs) {
        // No-op
    }

    @Override
    public void incrementMessagesReceived(String messageType) {
        // No-op
    }

    @Override
    public void incrementActiveConnections() {
        // No-op
    }

    @Override
    public void decrementActiveConnections() {
        // No-op
    }

    @Override
    public void incrementConnectionErrors() {
        // No-op
    }

    @Override
    public void recordTokenFetchDuration(String tokenType, long durationMs) {
        // No-op
    }

    @Override
    public void setCacheSize(int size) {
        // No-op
    }
}
