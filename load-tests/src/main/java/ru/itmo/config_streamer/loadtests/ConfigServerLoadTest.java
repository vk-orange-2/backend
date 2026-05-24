package ru.itmo.config_streamer.loadtests;

import us.abstracta.jmeter.javadsl.core.TestPlanStats;

import java.io.IOException;
import java.time.Instant;

import static us.abstracta.jmeter.javadsl.JmeterDsl.*;

/**
 * Load test for config server using JMeter Java DSL.
 * Tests create, update, and rollout config operations.
 * 
 * Configuration via (in order of priority):
 * 1. Command-line: -Dconfig.server.url=http://... -Dtest.threads=20
 * 2. user.properties file in working directory
 * 3. Default values
 * 
 * Usage:
 *   ./gradlew run
 *   ./gradlew run -Dconfig.server.url=http://localhost:8080 -Dtest.threads=50
 * 
 * Create user.properties file:
 *   config.server.url=http://localhost:8080
 *   test.threads=10
 *   test.iterations=10
 */
public class ConfigServerLoadTest {

    private static final String DEFAULT_CONFIG_SERVER_URL = "http://localhost:8080";
    private static final int DEFAULT_THREADS = 10;
    private static final int DEFAULT_ITERATIONS = 10;
    private static final String SERVICE_NAME = "client-test-service";
    private static final String ENV_NAME = "dev";

    public static void main(String[] args) throws IOException {
        ConfigServerLoadTest test = new ConfigServerLoadTest();
        test.runLoadTest();
    }

    public void runLoadTest() throws IOException {
        // Read configuration from system properties
        String configServerUrl = getProp("config.server.url", DEFAULT_CONFIG_SERVER_URL);
        int threads = getIntProp("test.threads", DEFAULT_THREADS);
        int iterations = getIntProp("test.iterations", DEFAULT_ITERATIONS);

        // Use timestamp to make configs unique per run
        String runId = String.valueOf(Instant.now().getEpochSecond());

        System.out.println("\n=== Config Server Load Test ===");
        System.out.printf("Configuration: URL=%s, Threads=%d, Iterations=%d%n", 
            configServerUrl, threads, iterations);
        System.out.printf("Run ID: %s%n%n", runId);

        TestPlanStats stats = testPlan(
            // Configurable threads and iterations
            threadGroup("Config Operations", threads, iterations,
                // Create Config - POST /v1/configs (upsert by service+env+key)
                // Uses unique key per run to avoid conflicts
                // ${__counter(TRUE,)} gives a per-thread counter
                httpSampler("Create Config", configServerUrl + "/v1/configs")
                    .method("POST")
                    .header("Content-Type", "application/json")
                    .body(String.format("""
                        {
                          "service": "%s",
                          "env": "%s",
                          "key": "config-%s-${__threadNum}-${__counter(TRUE,)}",
                          "value": {
                            "setting1": "value-${__threadNum}",
                            "setting2": 123
                          }
                        }
                        """, SERVICE_NAME, ENV_NAME, runId))
                    .children(
                        // Extract config ID from response using regex
                        regexExtractor("configId", "\"id\":\"([^\"]+)\""),
                        // Extract current version for update (version 1)
                        regexExtractor("createVersion", "\"currentVersion\":(\\d+)")
                    ),
                
                // Update Config - PUT /v1/configs/{id}
                // Requires expectedVersion for optimistic locking
                httpSampler("Update Config", configServerUrl + "/v1/configs/${configId}")
                    .method("PUT")
                    .header("Content-Type", "application/json")
                    .body("""
                        {
                          "value": {
                            "setting1": "updated-${__threadNum}",
                            "setting2": 456
                          },
                          "expectedVersion": ${createVersion}
                        }
                        """)
                    .children(
                        // Extract updated version for cleanup (version 2)
                        regexExtractor("updatedVersion", "\"currentVersion\":(\\d+)")
                    ),
                
                // Get Configs - GET /v1/configs?serviceName=...&environment=...
                // Note: GET uses different param names than POST!
                httpSampler("List Configs", configServerUrl + "/v1/configs")
                    .method("GET")
                    .param("serviceName", SERVICE_NAME)
                    .param("environment", ENV_NAME),
                
                // Start Rollout - POST /v1/rollouts (instant rollout for load test)
                httpSampler("Start Rollout", configServerUrl + "/v1/rollouts")
                    .method("POST")
                    .header("Content-Type", "application/json")
                    .body("""
                        {
                          "configId": "${configId}",
                          "type": "instant"
                        }
                        """),
                
                // Delete Config - DELETE /v1/configs/{id}
                // Cleanup: delete the config created in this iteration
                httpSampler("Delete Config", configServerUrl + "/v1/configs/${configId}")
                    .method("DELETE")
                    .header("Content-Type", "application/json")
                    .body("{\"expectedVersion\": ${updatedVersion}}")
            ),
            
            // Summary report
            htmlReporter("load-test-report.html")
        ).run();

        // Print summary
        System.out.println("\n=== Test Summary ===");
        System.out.printf("Configuration: URL=%s, Threads=%d, Iterations=%d%n", configServerUrl, threads, iterations);
        System.out.printf("Total samples: %d%n", stats.overall().samplesCount());
        System.out.printf("Average response time: %dms%n", stats.overall().sampleTime().mean().toMillis());
        System.out.printf("P99 response time: %dms%n", stats.overall().sampleTime().perc99().toMillis());
    }

    private String getProp(String name, String defaultValue) {
        String value = System.getProperty(name);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        return defaultValue;
    }

    private int getIntProp(String name, int defaultValue) {
        String value = getProp(name, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            System.err.println("Warning: Invalid value for " + name + ", using default: " + defaultValue);
            return defaultValue;
        }
    }
}
