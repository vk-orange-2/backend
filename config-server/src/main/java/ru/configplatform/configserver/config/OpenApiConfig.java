package ru.configplatform.configserver.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Config Platform API")
                        .version("1.0")
                        .description("Distributed Real-Time Configuration Delivery Platform. "
                                + "Manages configurations, versions, rollouts and audit."));
    }
}
