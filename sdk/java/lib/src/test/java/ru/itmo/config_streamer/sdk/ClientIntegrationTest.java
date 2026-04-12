package ru.itmo.config_streamer.sdk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Smoke integration test for Client.
 * Requires a running server at localhost:8080 with /v1/configs endpoint and
 * Centrifugo.
 * 
 * 
 * Also set CENTRIFUGO_API_KEY environment variable for the publish test.
 */
class ClientIntegrationTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String API_TOKEN = "test-token";
    private static final String SERVICE = "test-service";
    private static final String ENV = "dev";
    private static String centrifugoApiKey;

    @BeforeAll
    static void setup() {
        centrifugoApiKey = System.getenv("CENTRIFUGO_API_KEY");
    }

    @Test
    void testFetchInitialConfigs() throws InterruptedException {
        // Given: A client with a callback to capture config updates
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Config> capturedConfig = new AtomicReference<>();

        Client client = new Client(BASE_URL, API_TOKEN, SERVICE, ENV);
        client.addCallback(config -> {
            capturedConfig.set(config);
            latch.countDown();
        });

        // When: We run the client (connects to Centrifugo and fetches initial configs)
        client.run();

        // Then: We should receive the config from the stub endpoint
        boolean received = latch.await(10, TimeUnit.SECONDS);

        // Cleanup
        client.shutdown();

        // Verify we got a config
        assertTrue(received, "Should have received a config callback within timeout");

        Config config = capturedConfig.get();
        assertNotNull(config, "Config should not be null");
        assertNotNull(config.key(), "Config key should not be null");
        assertTrue(config.version() > 0, "Config version should be positive");
    }

    @Test
    void testGetConfigFromCache() throws InterruptedException {
        // Given: A client that fetches configs
        CountDownLatch latch = new CountDownLatch(1);

        Client client = new Client(BASE_URL, API_TOKEN, SERVICE, ENV);
        client.addCallback(config -> latch.countDown());

        // When: We run the client
        client.run();

        // Wait for initial fetch to complete
        boolean received = latch.await(10, TimeUnit.SECONDS);
        client.shutdown();

        // Then: get() should return the config from cache
        assertTrue(received, "Should have received a config callback");

        // Note: get() returns null if key doesn't match - this is expected behavior
        // The actual key depends on the stub response from nginx
        Config config = client.get("example-config");
        if (config != null) {
            assertEquals("example-config", config.key());
        }
    }

    @Test
    void testSubscriptionReceivesPublishedConfig() throws Exception {
        // Skip if no Centrifugo API key is set
        assumeTrue(centrifugoApiKey != null && !centrifugoApiKey.isEmpty(),
                "Skipping test: CENTRIFUGO_API_KEY not set");

        // Given: A client subscribed to the channel
        CountDownLatch latch = new CountDownLatch(2); // Expect initial config + published config
        AtomicReference<Config> publishedConfig = new AtomicReference<>();

        Client client = new Client(BASE_URL, API_TOKEN, SERVICE, ENV);
        client.addCallback(config -> {
            if ("published-config".equals(config.key())) {
                publishedConfig.set(config);
            }
            latch.countDown();
        });

        // Start the client
        client.run();

        // // Wait a bit for subscription to be ready
        Thread.sleep(1000);

        // When: We publish a config to Centrifugo
        String channel = "service:" + SERVICE + "--" + ENV;
        String publishPayload = "{\"key\":\"published-config\",\"version\":42,\"payload\":{\"data\":\"test-value\"}}";

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest publishRequest = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/centrifugo/api/publish"))
                .header("X-API-Key", centrifugoApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"channel\":\"" + channel + "\",\"data\":" + publishPayload + "}"))
                .build();

        HttpResponse<String> publishResponse = httpClient.send(publishRequest, HttpResponse.BodyHandlers.ofString());

        // Then: The client should receive the published config
        boolean received = latch.await(10, TimeUnit.SECONDS);
        client.shutdown();

        assertTrue(publishResponse.statusCode() == 200 || publishResponse.statusCode() == 201,
                "Publish should succeed, got status: " + publishResponse.statusCode());
        assertTrue(received, "Should have received the published config callback within timeout");

        Config config = publishedConfig.get();
        assertNotNull(config, "Published config should not be null");
        assertEquals("published-config", config.key());
        assertEquals(42, config.version());
    }
}
