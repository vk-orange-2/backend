package ru.itmo.config_streamer.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ru.itmo.config_streamer.sdk.Client;

@Configuration
public class Config {
    @Value("${config-streamer.base-url}")
    private String baseUrl;

    @Value("${config-streamer.api-key}")
    private String apiKey;

    @Bean
    Client configStreamerClient() {
        var client = new Client(baseUrl, apiKey);
        client.run();
        return client;
    }
}
