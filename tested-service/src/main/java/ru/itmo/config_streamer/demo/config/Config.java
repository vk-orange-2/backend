package ru.itmo.config_streamer.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ru.itmo.config_streamer.sdk.Client;

@Configuration
public class Config {
    @Bean
    Client configStreamerClient() {
        var client = new Client("http://localhost:8080", "token", "test-service", "dev");
        client.run();
        return client;
    }
}
