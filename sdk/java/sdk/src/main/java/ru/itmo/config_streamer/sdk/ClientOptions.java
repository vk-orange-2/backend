package ru.itmo.config_streamer.sdk;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Configuration options for the Client SDK.
 */
public class ClientOptions {
    private final MeterRegistry meterRegistry;

    private ClientOptions(Builder builder) {
        this.meterRegistry = builder.meterRegistry;
    }

    /**
     * Gets the MeterRegistry for metrics, or null if metrics are disabled.
     * @return the MeterRegistry or null
     */
    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }

    /**
     * Creates a new Builder for ClientOptions.
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates default options with metrics disabled.
     * @return default ClientOptions instance
     */
    public static ClientOptions defaults() {
        return builder().build();
    }

    /**
     * Builder for ClientOptions.
     */
    public static class Builder {
        private MeterRegistry meterRegistry = null;

        /**
         * Sets the MeterRegistry for metrics recording.
         * @param meterRegistry the Micrometer MeterRegistry (from Spring Boot Actuator)
         * @return this builder
         */
        public Builder meterRegistry(MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
            return this;
        }

        /**
         * Builds the ClientOptions instance.
         * @return a new ClientOptions instance
         */
        public ClientOptions build() {
            return new ClientOptions(this);
        }
    }
}
