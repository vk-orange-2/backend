package ru.itmo.config_streamer.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;
import ru.itmo.config_streamer.sdk.Client;
import ru.itmo.config_streamer.sdk.ClientOptions;

@Configuration
public class Config {
    @Value("${config-streamer.base-url}")
    private String baseUrl;

    @Value("${config-streamer.api-key}")
    private String apiKey;

    @Bean
    Client configStreamerClient(MeterRegistry meterRegistry) {
        var client = new Client(baseUrl, apiKey, 
                ClientOptions.builder().meterRegistry(meterRegistry).build());
        client.run();
        return client;
    }
}
