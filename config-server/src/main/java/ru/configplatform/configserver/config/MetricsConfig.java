package ru.configplatform.configserver.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> commonTags(
            @Value("${spring.application.name:config-server}") String applicationName
    ) {
        return registry -> registry.config()
                .commonTags("application", applicationName);
    }

    @Bean
    MeterFilter denyHighCardinalityUriTags() {
        return MeterFilter.deny(id ->
                "http.server.requests".equals(id.getName())
                        && id.getTag("uri") != null
                        && id.getTag("uri").contains("/v1/configs/")
                        && id.getTag("uri").contains("/versions/")
        );
    }
}
