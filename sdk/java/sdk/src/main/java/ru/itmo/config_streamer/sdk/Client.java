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
import ru.itmo.config_streamer.sdk.dto.CentrifugoMessage;
import ru.itmo.config_streamer.sdk.dto.ConfigStateResponse;
import ru.itmo.config_streamer.sdk.dto.ConfigStateResponse.ConfigStateEntry;
import ru.itmo.config_streamer.sdk.dto.ConfigStateResponse.GradualRolloutState;
import ru.itmo.config_streamer.sdk.dto.ConfigStateResponse.CanaryState;

/**
 * Main client for receiving configuration updates via Centrifugo WebSocket.
 * Supports gradual rollout and canary deployment functionality for staged configuration deployments.
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

        this.gradualRolloutManager = new GradualRolloutManager(instanceName);
        this.centrifugoManager = new CentrifugoManager(baseUrl, baseChannel, tokenFetcher, this::handlePublication);
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

        centrifugoManager.connect(connectionToken, subscriptionToken, this::fetchInitialState);
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

    /**
     * Fetches initial state using the new /v1/services/{name}/envs/{env}/state endpoint.
     * Handles global versions, gradual rollouts, and canary states.
     */
    private void fetchInitialState() {
        if (serviceName == null || envName == null) {
            LOGGER.warning("Cannot fetch state: serviceName or envName not set");
            return;
        }

        try {
            String url = baseUrl + "/v1/services/" +
                    URLEncoder.encode(serviceName, StandardCharsets.UTF_8) +
                    "/envs/" + URLEncoder.encode(envName, StandardCharsets.UTF_8) +
                    "/state";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                parseAndCacheState(response.body());
            } else {
                LOGGER.warning("Failed to fetch initial state. Status: " + response.statusCode());
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error fetching initial state", e);
        }
    }

    /**
     * Parses the ConfigStateResponse and populates the config cache.
     * Determines the appropriate version for each config based on:
     * - globalVersion/globalPayload: version rolled out to all instances
     * - gradualRollout: if active and instance is in current deployment, use target version
     * - canary: if active and instance is in canary percentage, use canary version
     * - latestVersion/latestPayload: fallback if no global version exists
     */
    private void parseAndCacheState(String responseBody) {
        try {
            ConfigStateResponse response = objectMapper.readValue(responseBody, ConfigStateResponse.class);

            if (response.configs == null || response.configs.isEmpty()) {
                LOGGER.info("No configs found in state response");
                return;
            }

            cacheLock.writeLock().lock();
            try {
                for (ConfigStateEntry entry : response.configs) {
                    if (entry.configKey == null) continue;
                    
                    Config config = determineConfigVersion(entry);
                    if (config != null) {
                        configCache.put(entry.configKey, config);
                        LOGGER.info("Initialized config '" + entry.configKey + "' to version " + config.version());
                    }
                }
            } finally {
                cacheLock.writeLock().unlock();
            }
            notifyCallbacksForAllConfigs();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error parsing state response", e);
        }
    }

    /**
     * Determines the appropriate config version based on the state entry.
     * Priority: canary > gradual rollout > global > latest
     */
    private Config determineConfigVersion(ConfigStateEntry entry) {
        String key = entry.configKey;
        
        // Check if this instance should receive canary version
        if (entry.canary != null && "completed".equals(entry.canary.status)) {
            if (gradualRolloutManager.isInCanary(entry.canary.percentage)) {
                LOGGER.info("Instance is in canary for config '" + key + 
                        "' using version " + entry.canary.canaryVersion);
                return new Config(key, entry.canary.canaryVersion, entry.canary.canaryPayload);
            }
        }
        
        // Check if this instance should receive gradual rollout version
        if (entry.gradualRollout != null && "in_progress".equals(entry.gradualRollout.status)) {
            GradualRolloutState gradual = entry.gradualRollout;
            int myBucket = gradualRolloutManager.calculateDeploymentBucket(
                    key, gradual.targetVersion, gradual.totalDeployments);
            
            // If this instance's bucket is within already deployed range
            if (myBucket <= gradual.currentDeployment) {
                LOGGER.info("Instance is in gradual rollout for config '" + key + 
                        "' bucket " + myBucket + " of " + gradual.totalDeployments + 
                        " using version " + gradual.targetVersion);
                return new Config(key, gradual.targetVersion, gradual.targetPayload);
            }
        }
        
        // Use global version if available (rolled out to all instances)
        if (entry.globalVersion != null && entry.globalVersion > 0) {
            return new Config(key, entry.globalVersion, entry.globalPayload);
        }
        
        // Fallback to latest version (may not be rolled out yet)
        if (entry.latestVersion != null && entry.latestVersion > 0) {
            LOGGER.info("Using latest version for config '" + key + 
                    "' (no global version available)");
            return new Config(key, entry.latestVersion, entry.latestPayload);
        }
        
        return null;
    }

    /**
     * Handles a publication from Centrifugo.
     * 
     * @param event the publication event
     */
    private void handlePublication(PublicationEvent event) {
        try {
            byte[] data = event.getData();
            if (data == null) return;

            CentrifugoMessage message = objectMapper.readValue(data, CentrifugoMessage.class);

            switch (message.type) {
                case "gradual_deploy" -> handleGradualDeploy(message);
                case "canary_deploy" -> handleCanaryDeploy(message);
                case "config_deleted" -> handleConfigDeleted(message);
                case "update" -> handleConfigUpdate(message);
                default -> LOGGER.warning("Unknown message type: " + message.type);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error handling publication", e);
        }
    }

    /**
     * Handles a gradual_deploy message.
     * Only processes the update if this instance's bucket matches the deployment number.
     */
    private void handleGradualDeploy(CentrifugoMessage message) {
        if (message.key == null || message.version == 0 || message.deployment == null || message.totalDeployments == null) {
            LOGGER.warning("Invalid gradual_deploy message: missing required fields");
            return;
        }

        // Check if this instance should process this deployment
        // Uses configKey and version to ensure different instance ordering per deployment
        if (!gradualRolloutManager.shouldProcessGradualDeploy(
                message.key, message.version, message.deployment, message.totalDeployments)) {
            return; // Not this instance's turn
        }

        LOGGER.info("Processing gradual_deploy for config '" + message.key + 
                "' v" + message.version + " deployment " + message.deployment + " of " + message.totalDeployments);
        
        handleConfigUpdate(message);
    }

    /**
     * Handles a canary_deploy message.
     * Only processes the update if this instance is in the canary percentage.
     */
    private void handleCanaryDeploy(CentrifugoMessage message) {
        if (message.key == null || message.version == 0 || message.canaryPercentage == null) {
            LOGGER.warning("Invalid canary_deploy message: missing required fields");
            return;
        }

        // Check if this instance is in the canary group
        if (!gradualRolloutManager.isInCanary(message.canaryPercentage)) {
            LOGGER.fine("Ignoring canary_deploy for config '" + message.key + 
                    "' - instance not in canary (percentage: " + message.canaryPercentage + "%)");
            return;
        }

        LOGGER.info("Processing canary_deploy for config '" + message.key + 
                "' v" + message.version + " (canary percentage: " + message.canaryPercentage + "%)");
        
        handleConfigUpdate(message);
    }

    /**
     * Handles a config_deleted message.
     * Removes the config from cache and notifies callbacks with null.
     */
    private void handleConfigDeleted(CentrifugoMessage message) {
        if (message.key == null) {
            LOGGER.warning("Invalid config_deleted message: missing key");
            return;
        }

        String key = message.key;
        Config removedConfig;
        
        cacheLock.writeLock().lock();
        try {
            removedConfig = configCache.remove(key);
        } finally {
            cacheLock.writeLock().unlock();
        }

        if (removedConfig != null) {
            LOGGER.info("Deleted config '" + key + "'");
        }
        
        // Notify callbacks with null to indicate deletion
        notifyCallbacksOfDeletion(key);
    }

    private void handleConfigUpdate(CentrifugoMessage message) {
        String key = message.key;
        long newVersion = message.version;

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

    /**
     * Notifies callbacks of a config deletion by passing null.
     */
    private void notifyCallbacksOfDeletion(String key) {
        for (Consumer<Config> callback : callbacks) {
            try {
                callback.accept(null);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in callback for deleted config: " + key, e);
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

}
