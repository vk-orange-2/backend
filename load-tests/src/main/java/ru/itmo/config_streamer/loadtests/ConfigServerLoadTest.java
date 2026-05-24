package ru.itmo.config_streamer.loadtests;

import us.abstracta.jmeter.javadsl.core.TestPlanStats;

import java.io.IOException;

import static us.abstracta.jmeter.javadsl.JmeterDsl.*;

/**
 * Load test for config server using JMeter Java DSL.
 * Tests create, update, and rollout config operations.
 */
public class ConfigServerLoadTest {

    private static final String CONFIG_SERVER_URL = "http://localhost:8080";
    private static final String SERVICE_NAME = "test-service";
    private static final String ENV_NAME = "dev";

    public static void main(String[] args) throws IOException {
        ConfigServerLoadTest test = new ConfigServerLoadTest();
        test.runLoadTest();
    }

    public void runLoadTest() throws IOException {
        TestPlanStats stats = testPlan(
            // 10 threads, 10 iterations each
            threadGroup("Config Operations", 10, 10,
                // Create Config
                httpSampler("Create Config", CONFIG_SERVER_URL + "/v1/configs")
                    .method("POST")
                    .header("Content-Type", "application/json")
                    .body(String.format("""
                        {
                          "serviceName": "%s",
                          "environment": "%s",
                          "configKey": "load-test-config-${__threadNum}-${__iteration}",
                          "payload": {
                            "value": "initial-value-${__threadNum}-${__iteration}"
                          }
                        }
                        """, SERVICE_NAME, ENV_NAME))
                    .children(
                        // Extract configId from response
                        jsonExtractor("configId", "$.id")
                    ),
                
                // Update Config
                httpSampler("Update Config", CONFIG_SERVER_URL + "/v1/configs/${configId}")
                    .method("PUT")
                    .header("Content-Type", "application/json")
                    .body("""
                        {
                          "payload": {
                            "value": "updated-value-${__threadNum}-${__iteration}"
                          }
                        }
                        """),
                
                // Rollout Config (Instant)
                httpSampler("Rollout Config", CONFIG_SERVER_URL + "/v1/rollouts")
                    .method("POST")
                    .header("Content-Type", "application/json")
                    .body("""
                        {
                          "configId": "${configId}",
                          "type": "instant"
                        }
                        """)
            ),
            
            // Summary report
            htmlReporter("load-test-report.html")
        ).run();

        // Print summary
        System.out.println("\n=== Config Operations Test Summary ===");
        System.out.printf("Total samples: %d%n", stats.overall().samplesCount());
        System.out.printf("Average response time: %dms%n", stats.overall().sampleTime().mean().toMillis());
        System.out.printf("P99 response time: %dms%n", stats.overall().sampleTime().perc99().toMillis());
    }
}
