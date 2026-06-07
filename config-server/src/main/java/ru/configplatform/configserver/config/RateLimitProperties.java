package ru.configplatform.configserver.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    private Subject user = new Subject();
    private Subject serviceAccount = new Subject();

    @Data
    public static class Subject {
        private Limit read = new Limit();
        private Limit write = new Limit();
    }

    @Data
    public static class Limit {
        private double rate;
        private double burst;
    }
}
