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

import io.github.centrifugal.centrifuge.ConnectedEvent;
import io.github.centrifugal.centrifuge.ConnectionTokenEvent;
import io.github.centrifugal.centrifuge.ConnectionTokenGetter;
import io.github.centrifugal.centrifuge.EventListener;
import io.github.centrifugal.centrifuge.Options;
import io.github.centrifugal.centrifuge.PublicationEvent;
import io.github.centrifugal.centrifuge.Subscription;
import io.github.centrifugal.centrifuge.SubscriptionEventListener;
import io.github.centrifugal.centrifuge.SubscriptionOptions;
import io.github.centrifugal.centrifuge.SubscriptionTokenEvent;
import io.github.centrifugal.centrifuge.SubscriptionTokenGetter;
import io.github.centrifugal.centrifuge.TokenCallback;

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
    private String channel;

    private final Map<String, Config> configCache = new HashMap<>();
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();

    private final List<Consumer<Config>> callbacks = new CopyOnWriteArrayList<>();

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private io.github.centrifugal.centrifuge.Client centrifugoClient;

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
    }

    /**
     * Adds a callback that will be invoked when a config is updated.
     * Callbacks are stored in a thread-safe CopyOnWriteArrayList.
     * 
     * @param callback the callback to be invoked on config updates
     */
    public void addCallback(Consumer<Config> callback) {
        if (callback != null) {
            callbacks.add(callback);
        }
    }

    /**
     * Subscribes to centrifugo websocket channel.
     * Gets latest configs by service and env, stores them in cache, calls callbacks
     * for each config.
     * 
     * To get latest configs it sends an HTTP request on
     * /v1/configs?serviceName=...&environment=...
     * 
     * When a publication arrives to the centrifugo channel - stores in cache, calls
     * callback. If config version is less than current - ignore this version, just
     * log it.
     * 
     * Centrifugo will send messages like: {"key": "some_key", "version": 4,
     * "payload": "DATA"}
     */
    public void run() {
        // First, fetch initial JWT token synchronously to extract channel info
        String initialToken = fetchJwtToken();
        if (initialToken == null) {
            throw new RuntimeException("Failed to obtain initial JWT token");
        }

        // Extract channel from JWT to get serviceName and envName
        extractChannelFromJwt(initialToken);

        if (serviceName == null || envName == null || channel == null) {
            throw new RuntimeException("Failed to extract channel info from JWT token");
        }

        connectToCentrifugo(initialToken);
    }

    public Config get(String key) {
        cacheLock.readLock().lock();
        try {
            var config = configCache.get(key);
            if (config == null) {
                return null;
            }
            return config.clone();
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    public void shutdown() {
        if (centrifugoClient != null) {
            try {
                centrifugoClient.disconnect();
                LOGGER.info("Disconnected from Centrifugo");
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error disconnecting from Centrifugo", e);
            }
        }
    }

    /**
     * Fetches a JWT token from the config server using the API key.
     * This token is used for both Centrifugo connection and subscription authentication.
     *
     * @return the JWT token string, or null if the request failed
     */
    private String fetchJwtToken() {
        try {
            String url = baseUrl + "/v1/api-keys/exchange" +
                    "?apiKey=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8) +
                    "&serviceId=" + serviceId +
                    "&environmentId=" + environmentId +
                    "&instanceName=" + URLEncoder.encode(instanceName, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String token = response.body();
                if (token != null && !token.isEmpty()) {
                    LOGGER.fine("Successfully obtained JWT token");
                    return token;
                }
            }

            LOGGER.warning("Failed to obtain JWT token. Status: " + response.statusCode());
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error fetching JWT token", e);
            return null;
        }
    }

    /**
     * Extracts the channel claim from a JWT token and populates serviceName, envName, and channel.
     * The channel format is "service:<service_name>:<env_code>".
     *
     * @param token the JWT token
     */
    private void extractChannelFromJwt(String token) {
        try {
            // JWT format: header.payload.signature
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                LOGGER.warning("Invalid JWT token format");
                return;
            }

            // Decode the payload (middle part)
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);

            // Parse JSON to extract channel claim
            Map<String, Object> claims = objectMapper.readValue(payload, Map.class);
            String channelValue = (String) claims.get("channel");

            if (channelValue != null && channelValue.startsWith("service:")) {
                this.channel = channelValue;
                String[] channelParts = channelValue.substring("service:".length()).split(":", 2);
                if (channelParts.length == 2) {
                    this.serviceName = channelParts[0];
                    this.envName = channelParts[1];
                    LOGGER.info("Extracted from JWT - serviceName: " + serviceName + ", envName: " + envName + ", channel: " + channel);
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
                        String key = item.configKey;
                        int version = item.currentVersion;

                        Config config = new Config(key, version, item.latestVersion.payload);
                        configCache.put(key, config);
                    }
                } finally {
                    cacheLock.writeLock().unlock();
                }

                // Notify callbacks outside the lock to prevent deadlocks
                notifyCallbacksForAllConfigs();
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error parsing configs response", e);
        }
    }

    private void connectToCentrifugo(String initialToken) {
        try {
                Options options = new Options();
            // Set initial token for connection
            options.setToken(initialToken);
            // Set token getter for connection-level authentication (for refresh)
            options.setTokenGetter(new ConnectionTokenGetter() {
                @Override
                public void getConnectionToken(ConnectionTokenEvent event, TokenCallback cb) {
                    String token = fetchJwtToken();
                    if (token == null) {
                        cb.Done(new RuntimeException("Failed to obtain JWT token for connection"), null);
                    } else {
                        cb.Done(null, token);
                    }
                }
            });

            String wsUrl = baseUrl.replace("http://", "ws://")
                    .replace("https://", "wss://")
                    + "/centrifugo/connection/websocket";

            centrifugoClient = new io.github.centrifugal.centrifuge.Client(wsUrl, options, new EventListener() {
                @Override
                public void onConnected(io.github.centrifugal.centrifuge.Client client, ConnectedEvent event) {
                    LOGGER.info("Connected to Centrifugo, fetching fresh configs");
                    fetchInitialConfigs();
                }
            });

            // Connect first
            centrifugoClient.connect();

            // Create subscription options with token getter for subscription-level authentication
            SubscriptionOptions subOptions = new SubscriptionOptions();
            // Set initial token
            subOptions.setToken(initialToken);
            // Set token getter for automatic refresh
            subOptions.setTokenGetter(new SubscriptionTokenGetter() {
                @Override
                public void getSubscriptionToken(SubscriptionTokenEvent event, TokenCallback cb) {
                    String token = fetchJwtToken();
                    if (token == null) {
                        cb.Done(new RuntimeException("Failed to obtain JWT token for subscription"), null);
                    } else {
                        cb.Done(null, token);
                    }
                }
            });

            SubscriptionEventListener subListener = new SubscriptionEventListener() {
                @Override
                public void onPublication(Subscription sub, PublicationEvent event) {
                    handlePublication(event);
                }
            };

            // Use the channel extracted from JWT
            Subscription subscription = centrifugoClient.newSubscription(channel, subOptions, subListener);
            subscription.subscribe();

            LOGGER.info("Connected to Centrifugo and subscribed to channel: " + channel);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error connecting to Centrifugo", e);
        }
    }

    /**
     * Handles a publication from Centrifugo.
     * Parses the message and updates the cache if the version is newer.
     * Uses write lock for thread-safe cache update with atomic version check.
     */
    private void handlePublication(PublicationEvent event) {
        try {
            byte[] data = event.getData();

            if (data != null) {
                CentrifugoMessage message = objectMapper.readValue(data, CentrifugoMessage.class);

                String key = message.key;
                int newVersion = message.version;

                // Thread-safe version check and update - must be atomic
                Config newConfig = null;
                cacheLock.writeLock().lock();
                try {
                    Config currentConfig = configCache.get(key);

                    if (currentConfig == null || newVersion > currentConfig.version()) {
                        newConfig = new Config(key, newVersion, message.payload);
                        configCache.put(key, newConfig);

                        LOGGER.info("Updated config '" + key + "' to version " + newVersion);
                    } else {
                        LOGGER.fine("Ignoring outdated config '" + key + "' version " + newVersion +
                                ", current version is " + currentConfig.version());
                    }
                } finally {
                    cacheLock.writeLock().unlock();
                }

                // Notify callbacks outside the lock to prevent deadlocks
                if (newConfig != null) {
                    notifyCallbacks(newConfig);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error handling publication", e);
        }
    }

    /**
     * Notifies all registered callbacks about a config update.
     * Should be called outside of any locks to prevent deadlocks.
     */
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
     * Notifies callbacks for all configs currently in cache.
     * Used after initial fetch. Called outside the lock.
     */
    private void notifyCallbacksForAllConfigs() {
        List<Config> configsToNotify;
        cacheLock.readLock().lock();
        try {
            configsToNotify = new ArrayList<>(configCache.values());
        } finally {
            cacheLock.readLock().unlock();
        }

        for (Config config : configsToNotify) {
            notifyCallbacks(config);
        }
    }

    // --- DTO classes for JSON deserialization ---

    /**
     * Response from /v1/configs endpoint
     */
    private static class ConfigListResponse {
        public List<ConfigItem> configs;
    }

    /**
     * Individual config item from the API response
     */
    private static class ConfigItem {
        public String configKey;
        public int currentVersion;
        public VersionInfo latestVersion;
    }

    /**
     * Version information including payload.
     * Uses Object to accept any JSON value (object, array, string, number, boolean,
     * null).
     * Serialized to byte[] for storage in Config.
     */
    private static class VersionInfo {
        public Object payload;
    }

    /**
     * Message received from Centrifugo publication.
     * Uses Object to accept any JSON value (object, array, string, number, boolean,
     * null).
     * Serialized to byte[] for storage in Config.
     */
    private static class CentrifugoMessage {
        public String key;
        public int version;
        public Object payload;
    }
}
