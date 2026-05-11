package ru.itmo.config_streamer.sdk;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles fetching JWT tokens from the config server.
 */
class TokenFetcher {
    private static final Logger LOGGER = Logger.getLogger(TokenFetcher.class.getName());

    private final String baseUrl;
    private final UUID serviceId;
    private final short environmentId;
    private final String apiKey;
    private final String instanceName;
    private final HttpClient httpClient;

    TokenFetcher(String baseUrl, UUID serviceId, short environmentId, String apiKey, String instanceName) {
        this.baseUrl = baseUrl;
        this.serviceId = serviceId;
        this.environmentId = environmentId;
        this.apiKey = apiKey;
        this.instanceName = instanceName;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Fetches a connection JWT token from the config server.
     */
    String fetchConnectionToken() {
        String url = buildUrl("/v1/api-keys/connection-token");
        return fetchToken(url, "connection");
    }

    /**
     * Fetches a subscription JWT token for the base channel.
     */
    String fetchSubscriptionToken() {
        String url = buildUrl("/v1/api-keys/subscription-token");
        return fetchToken(url, "subscription (base channel)");
    }

    /**
     * Fetches a subscription JWT token for a gradual rollout channel.
     *
     * @param rolloutKey the rollout key identifier
     * @param percentage the percentage bucket (1-100)
     */
    String fetchSubscriptionTokenForGradualChannel(String rolloutKey, int percentage) {
        String url = buildUrl("/v1/api-keys/subscription-token") +
                "&rolloutKey=" + URLEncoder.encode(rolloutKey, StandardCharsets.UTF_8) +
                "&percentage=" + percentage;
        return fetchToken(url, "subscription (gradual channel: " + rolloutKey + ":" + percentage + "%)");
    }

    private String buildUrl(String endpoint) {
        return baseUrl + endpoint +
                "?apiKey=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8) +
                "&serviceId=" + serviceId +
                "&environmentId=" + environmentId +
                "&instanceName=" + URLEncoder.encode(instanceName, StandardCharsets.UTF_8);
    }

    private String fetchToken(String url, String tokenType) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String token = response.body();
                if (token != null && !token.isEmpty()) {
                    LOGGER.fine("Successfully obtained " + tokenType + " JWT token");
                    return token;
                }
            }

            LOGGER.warning("Failed to obtain " + tokenType + " JWT token. Status: " + response.statusCode() +
                    ", body: " + response.body());
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error fetching " + tokenType + " JWT token", e);
            return null;
        }
    }
}
