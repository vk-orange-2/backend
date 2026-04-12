package ru.itmo.config_streamer.sdk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.centrifugal.centrifuge.ConnectedEvent;
import io.github.centrifugal.centrifuge.EventListener;
import io.github.centrifugal.centrifuge.Options;
import io.github.centrifugal.centrifuge.PublicationEvent;
import io.github.centrifugal.centrifuge.Subscription;
import io.github.centrifugal.centrifuge.SubscriptionEventListener;

public class Client {
    private static final Logger LOGGER = Logger.getLogger(Client.class.getName());

    private final String baseUrl;
    private final String apiToken;
    private final String service;
    private final String env;

    // Cache with ReadWriteLock for thread-safe access
    // Using ReadWriteLock instead of ConcurrentHashMap because we need atomic
    // check-and-update operations (check version, then update if newer)
    private final Map<String, Config> configCache = new HashMap<>();
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();

    // Thread-safe callbacks list - CopyOnWriteArrayList is ideal for
    // infrequent writes (adding callbacks) and frequent reads (iterating during
    // updates)
    private final List<Consumer<Config>> callbacks = new CopyOnWriteArrayList<>();

    // HTTP client for fetching configs
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // Centrifugo client - using fully qualified name to avoid conflict with our
    // Client class
    private io.github.centrifugal.centrifuge.Client centrifugoClient;

    public Client(final String baseUrl, final String apiToken, final String service, final String env) {
        this.baseUrl = baseUrl;
        this.apiToken = apiToken;
        this.service = service;
        this.env = env;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
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
     * Subscribes to centrifugo websocket channel named "service:<service>--<env>".
     * Gets latest configs by service and env, stores them in cache, calls callbacks
     * for each config.
     * 
     * To get latest configs it sends an HTTP request on
     * /v1/configs?serviceName=this.service&environment=this.env
     * 
     * When a publication arrives to the centrifugo channel - stores in cache, calls
     * callback. If config version is less than current - ignore this version, just
     * log it.
     * 
     * Centrifugo will send messages like: {"key": "some_key", "version": 4,
     * "payload": "DATA"}
     */
    public void run() {
        connectToCentrifugo();
    }

    public Config get(String key) {
        cacheLock.readLock().lock();
        try {
            return configCache.get(key);
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

    private void fetchInitialConfigs() {
        try {
            String url = baseUrl + "/v1/configs?serviceName=" + service + "&environment=" + env;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiToken)
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
                        byte[] payload = null;

                        if (item.latestVersion != null && item.latestVersion.payload != null) {
                            payload = objectMapper.writeValueAsBytes(item.latestVersion.payload);
                        }

                        Config config = new Config(key, version, payload);
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

    private void connectToCentrifugo() {
        try {
            String channel = "service:" + service + ":" + env;

            Options options = new Options();

            // options.setToken(apiToken);

            SubscriptionEventListener subListener = new SubscriptionEventListener() {
                @Override
                public void onPublication(Subscription sub, PublicationEvent event) {
                    handlePublication(event);
                }
            };

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

            // Connect and subscribe
            centrifugoClient.connect();

            Subscription subscription = centrifugoClient.newSubscription(channel, subListener);
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
                byte[] payload = message.payload != null
                        ? objectMapper.writeValueAsBytes(message.payload)
                        : null;

                // Thread-safe version check and update - must be atomic
                Config newConfig = null;
                cacheLock.writeLock().lock();
                try {
                    Config currentConfig = configCache.get(key);

                    if (currentConfig == null || newVersion > currentConfig.version()) {
                        newConfig = new Config(key, newVersion, payload);
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
