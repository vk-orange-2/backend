package ru.itmo.config_streamer.sdk;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.centrifugal.centrifuge.PublicationEvent;

/**
 * Main client for receiving configuration updates via Centrifugo WebSocket.
 * Supports gradual rollout functionality for staged configuration deployments.
 */
public class Client {
    private static final Logger LOGGER = Logger.getLogger(Client.class.getName());

    private final String baseUrl;
    private final UUID serviceId;
    private final short environmentId;
    private final String apiKey;
    private final String instanceName;

    // Extracted from JWT channel claim
    private String serviceName;
    private String envName;
    private String baseChannel;

    private final Map<String, Config> configCache = new HashMap<>();
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private final List<Consumer<Config>> callbacks = new CopyOnWriteArrayList<>();

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final TokenFetcher tokenFetcher;
    private final GradualRolloutManager gradualRolloutManager;
    private final CentrifugoManager centrifugoManager;

    /**
     * Creates a new Client instance.
     *
     * @param baseUrl the base URL of the config server (e.g., "http://localhost:8080")
     * @param apiKey  the full API key in format "serviceId:environmentId:keyValue"
     */
    public Client(final String baseUrl, final String apiKey) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.instanceName = UUID.randomUUID().toString();

        // Parse API key: format is "serviceId:environmentId:keyValue"
        String[] parts = apiKey.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "Invalid API key format. Expected 'serviceId:environmentId:keyValue'");
        }
        this.serviceId = UUID.fromString(parts[0]);
        this.environmentId = Short.parseShort(parts[1]);
        this.apiKey = parts[2];

        this.tokenFetcher = new TokenFetcher(baseUrl, serviceId, environmentId, apiKey, instanceName);

        // Fetch subscription token to extract channel info and initialize managers
        String subscriptionToken = fetchSubscriptionTokenSafely();
        extractChannelFromJwt(subscriptionToken);

        if (serviceName == null || envName == null || baseChannel == null) {
            throw new RuntimeException("Failed to extract channel info from JWT");
        }

        this.gradualRolloutManager = new GradualRolloutManager(baseChannel, instanceName);
        this.centrifugoManager = new CentrifugoManager(baseUrl, baseChannel, tokenFetcher,
                gradualRolloutManager, this::handlePublication);
    }

    /**
     * Adds a callback that will be invoked when a config is updated.
     *
     * @param callback the callback to be invoked on config updates
     */
    public void addCallback(Consumer<Config> callback) {
        if (callback != null) {
            callbacks.add(callback);
        }
    }

    /**
     * Subscribes to centrifugo websocket channel and starts receiving updates.
     */
    public void run() {
        String connectionToken = fetchConnectionTokenSafely();
        String subscriptionToken = fetchSubscriptionTokenSafely();

        centrifugoManager.connect(connectionToken, subscriptionToken, this::fetchInitialConfigs);
    }

    /**
     * Gets a config by key.
     *
     * @param key the config key
     * @return the config, or null if not found
     */
    public Config get(String key) {
        cacheLock.readLock().lock();
        try {
            var config = configCache.get(key);
            return config == null ? null : config.clone();
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Shuts down the client and disconnects from Centrifugo.
     */
    public void shutdown() {
        if (centrifugoManager != null) {
            centrifugoManager.disconnect();
        }
    }

    private String fetchConnectionTokenSafely() {
        String token = tokenFetcher.fetchConnectionToken();
        if (token == null) {
            throw new RuntimeException("Failed to obtain connection JWT token. Check API key and server availability.");
        }
        return token;
    }

    private String fetchSubscriptionTokenSafely() {
        String token = tokenFetcher.fetchSubscriptionToken();
        if (token == null) {
            throw new RuntimeException("Failed to obtain subscription JWT token. Check API key and server availability.");
        }
        return token;
    }

    /**
     * Extracts the channel claim from a JWT token and populates serviceName, envName, and baseChannel.
     */
    private void extractChannelFromJwt(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                LOGGER.warning("Invalid JWT token format");
                return;
            }

            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Map<String, Object> claims = objectMapper.readValue(payload, Map.class);
            String channelValue = (String) claims.get("channel");

            if (channelValue != null && channelValue.startsWith("service:")) {
                this.baseChannel = channelValue;
                String[] channelParts = channelValue.substring("service:".length()).split(":", 2);
                if (channelParts.length == 2) {
                    this.serviceName = channelParts[0];
                    this.envName = channelParts[1];
                    LOGGER.info("Extracted from JWT - serviceName: " + serviceName + ", envName: " + envName + ", baseChannel: " + baseChannel);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error extracting channel from JWT", e);
        }
    }

    private void fetchInitialConfigs() {
        if (serviceName == null || envName == null) {
            LOGGER.warning("Cannot fetch configs: serviceName or envName not set");
            return;
        }

        try {
            String url = baseUrl + "/v1/configs?serviceName=" +
                    URLEncoder.encode(serviceName, StandardCharsets.UTF_8) +
                    "&environment=" + URLEncoder.encode(envName, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                parseAndCacheConfigs(response.body());
            } else {
                LOGGER.warning("Failed to fetch initial configs. Status: " + response.statusCode());
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error fetching initial configs", e);
        }
    }

    private void parseAndCacheConfigs(String responseBody) {
        try {
            ConfigListResponse response = objectMapper.readValue(responseBody, ConfigListResponse.class);

            if (response.configs != null) {
                cacheLock.writeLock().lock();
                try {
                    for (ConfigItem item : response.configs) {
                        Config config = new Config(item.configKey, item.currentVersion, item.latestVersion.payload);
                        configCache.put(item.configKey, config);
                    }
                } finally {
                    cacheLock.writeLock().unlock();
                }
                notifyCallbacksForAllConfigs();
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error parsing configs response", e);
        }
    }

    /**
     * Handles a publication from Centrifugo.
     * 
     * @param event the publication event
     * @param fromGradualChannel true if this publication came from a gradual rollout channel
     */
    private void handlePublication(PublicationEvent event, boolean fromGradualChannel) {
        try {
            byte[] data = event.getData();
            if (data == null) return;

            CentrifugoMessage message = objectMapper.readValue(data, CentrifugoMessage.class);

            if ("gradual_start".equals(message.type)) {
                handleGradualRollout(message);
                return;
            }

            handleConfigUpdate(message, fromGradualChannel);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error handling publication", e);
        }
    }

    private void handleGradualRollout(CentrifugoMessage message) {
        if (message.key == null || message.deployments == null) {
            LOGGER.warning("Invalid gradual rollout message: missing key or deployments");
            return;
        }

        int deploymentNumber = gradualRolloutManager.calculateDeploymentBucket(message.deployments);
        LOGGER.info("Gradual rollout: key=" + message.key + ", deployment " + deploymentNumber + " of " + message.deployments);
        centrifugoManager.subscribeToGradualChannel(message.key, deploymentNumber);
    }

    private void handleConfigUpdate(CentrifugoMessage message, boolean fromGradualChannel) {
        String key = message.key;
        int newVersion = message.version;

        Config newConfig = null;
        cacheLock.writeLock().lock();
        try {
            Config currentConfig = configCache.get(key);

            if (currentConfig == null || newVersion > currentConfig.version()) {
                newConfig = new Config(key, newVersion, message.payload);
                configCache.put(key, newConfig);
                LOGGER.info("Updated config '" + key + "' to version " + newVersion);
            } else {
                LOGGER.fine("Ignoring outdated config '" + key + "' version " + newVersion);
            }
        } finally {
            cacheLock.writeLock().unlock();
        }

        if (newConfig != null) {
            notifyCallbacks(newConfig);
        }

        // Auto-unsubscribe from gradual channel after receiving config update from it
        if (fromGradualChannel) {
            LOGGER.info("Received config update from gradual channel, unsubscribing...");
            centrifugoManager.unsubscribeFromGradualChannel();
        }
    }

    private void notifyCallbacks(Config config) {
        for (Consumer<Config> callback : callbacks) {
            try {
                callback.accept(config);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in callback for config: " + config.key(), e);
            }
        }
    }

    private void notifyCallbacksForAllConfigs() {
        List<Config> configsToNotify;
        cacheLock.readLock().lock();
        try {
            configsToNotify = new ArrayList<>(configCache.values());
        } finally {
            cacheLock.readLock().unlock();
        }
        configsToNotify.forEach(this::notifyCallbacks);
    }

    // --- DTO classes for JSON deserialization ---

    private static class ConfigListResponse {
        public List<ConfigItem> configs;
    }

    private static class ConfigItem {
        public String configKey;
        public int currentVersion;
        public VersionInfo latestVersion;
    }

    private static class VersionInfo {
        public Object payload;
    }

    private static class CentrifugoMessage {
        public String type;
        public String key;
        public int version;
        public Object payload;
        public Integer deployments;
    }
}
