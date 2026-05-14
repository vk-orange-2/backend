package ru.itmo.config_streamer.sdk;

import java.util.logging.Level;
import java.util.logging.Logger;

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

/**
 * Manages Centrifugo WebSocket connection and subscriptions.
 * Keeps base channel subscription always active and adds/removes gradual channel as needed.
 */
class CentrifugoManager {
    private static final Logger LOGGER = Logger.getLogger(CentrifugoManager.class.getName());

    private final String wsUrl;
    private final String baseChannel;
    private final TokenFetcher tokenFetcher;
    private final GradualRolloutManager gradualRolloutManager;
    private final PublicationHandler publicationHandler;

    private io.github.centrifugal.centrifuge.Client centrifugoClient;
    private Subscription baseSubscription;
    private Subscription gradualSubscription;
    private String currentGradualChannel;
    private String baseSubscriptionToken;

    /**
     * Functional interface for handling publications.
     * @param fromGradualChannel true if the publication came from a gradual rollout channel
     */
    interface PublicationHandler {
        void handle(PublicationEvent event, boolean fromGradualChannel);
    }

    CentrifugoManager(String baseUrl, String baseChannel, TokenFetcher tokenFetcher,
                      GradualRolloutManager gradualRolloutManager, PublicationHandler publicationHandler) {
        this.wsUrl = baseUrl.replace("http://", "ws://")
                .replace("https://", "wss://")
                + "/centrifugo/connection/websocket";
        this.baseChannel = baseChannel;
        this.tokenFetcher = tokenFetcher;
        this.gradualRolloutManager = gradualRolloutManager;
        this.publicationHandler = publicationHandler;
    }

    /**
     * Connects to Centrifugo with the given tokens.
     *
     * @param connectionToken the connection JWT token
     * @param subToken the subscription JWT token for base channel
     * @param onConnected callback to run when connected
     */
    void connect(String connectionToken, String subToken, Runnable onConnected) {
        this.baseSubscriptionToken = subToken;

        Options options = createOptions(connectionToken);
        centrifugoClient = new io.github.centrifugal.centrifuge.Client(wsUrl, options, new EventListener() {
            @Override
            public void onConnected(io.github.centrifugal.centrifuge.Client client, ConnectedEvent event) {
                LOGGER.info("Connected to Centrifugo");
                onConnected.run();
            }
        });

        centrifugoClient.connect();
        subscribeToBaseChannel();
        LOGGER.info("Connected to Centrifugo and subscribed to base channel: " + baseChannel);
    }

    /**
     * Subscribes to the base channel.
     */
    private void subscribeToBaseChannel() {
        try {
            SubscriptionOptions subOptions = new SubscriptionOptions();
            subOptions.setToken(baseSubscriptionToken);
            subOptions.setTokenGetter(new SubscriptionTokenGetter() {
                @Override
                public void getSubscriptionToken(SubscriptionTokenEvent event, TokenCallback cb) {
                    handleBaseTokenRefresh(cb);
                }
            });

            SubscriptionEventListener subListener = new SubscriptionEventListener() {
                @Override
                public void onPublication(Subscription sub, PublicationEvent event) {
                    publicationHandler.handle(event, false); // Base channel
                }
            };

            baseSubscription = centrifugoClient.newSubscription(baseChannel, subOptions, subListener);
            baseSubscription.subscribe();

            LOGGER.info("Subscribed to base channel: " + baseChannel);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error subscribing to base channel: " + baseChannel, e);
        }
    }

    /**
     * Subscribes to a gradual rollout channel (keeps base channel subscription).
     *
     * @param rolloutKey the rollout key
     * @param deploymentNumber the deployment number (1 to deployments)
     */
    void subscribeToGradualChannel(String rolloutKey, int deploymentNumber) {
        String gradualChannel = gradualRolloutManager.buildGradualChannel(rolloutKey, deploymentNumber);

        if (gradualChannel.equals(currentGradualChannel) && gradualSubscription != null) {
            LOGGER.fine("Already subscribed to gradual channel: " + gradualChannel);
            return;
        }

        // Unsubscribe from previous gradual channel if any
        unsubscribeFromGradualChannel();

        try {
            String token = tokenFetcher.fetchSubscriptionTokenForGradualChannel(rolloutKey, deploymentNumber);
            if (token == null) {
                LOGGER.severe("Failed to obtain subscription JWT token for gradual channel: " + gradualChannel);
                return;
            }

            final String finalChannel = gradualChannel;

            SubscriptionOptions subOptions = new SubscriptionOptions();
            subOptions.setToken(token);
            subOptions.setTokenGetter(new SubscriptionTokenGetter() {
                @Override
                public void getSubscriptionToken(SubscriptionTokenEvent event, TokenCallback cb) {
                    handleGradualTokenRefresh(finalChannel, cb);
                }
            });

            SubscriptionEventListener subListener = new SubscriptionEventListener() {
                @Override
                public void onPublication(Subscription sub, PublicationEvent event) {
                    publicationHandler.handle(event, true); // Gradual channel
                }
            };

            gradualSubscription = centrifugoClient.newSubscription(gradualChannel, subOptions, subListener);
            gradualSubscription.subscribe();
            currentGradualChannel = gradualChannel;

            LOGGER.info("Subscribed to gradual channel: " + gradualChannel);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error subscribing to gradual channel: " + gradualChannel, e);
        }
    }

    /**
     * Unsubscribes from the gradual channel (keeps base channel subscription).
     */
    void unsubscribeFromGradualChannel() {
        if (gradualSubscription != null) {
            try {
                gradualSubscription.unsubscribe();
                LOGGER.info("Unsubscribed from gradual channel: " + currentGradualChannel);
                gradualSubscription = null;
                currentGradualChannel = null;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error unsubscribing from gradual channel", e);
            }
        }
    }

    /**
     * Gets the current gradual channel (if any).
     */
    String getCurrentGradualChannel() {
        return currentGradualChannel;
    }

    /**
     * Disconnects from Centrifugo.
     */
    void disconnect() {
        if (centrifugoClient != null) {
            try {
                centrifugoClient.disconnect();
                LOGGER.info("Disconnected from Centrifugo");
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error disconnecting from Centrifugo", e);
            }
        }
    }

    private Options createOptions(String connectionToken) {
        Options options = new Options();
        options.setToken(connectionToken);
        options.setTokenGetter(new ConnectionTokenGetter() {
            @Override
            public void getConnectionToken(ConnectionTokenEvent event, TokenCallback cb) {
                String token = tokenFetcher.fetchConnectionToken();
                if (token == null) {
                    LOGGER.severe("Failed to refresh connection JWT token");
                    cb.Done(new RuntimeException("Failed to obtain connection JWT token"), null);
                } else {
                    LOGGER.fine("Refreshed connection JWT token");
                    cb.Done(null, token);
                }
            }
        });
        return options;
    }

    private void handleBaseTokenRefresh(TokenCallback cb) {
        String token = tokenFetcher.fetchSubscriptionToken();
        if (token == null) {
            LOGGER.severe("Failed to refresh base subscription JWT token");
            cb.Done(new RuntimeException("Failed to obtain subscription JWT token"), null);
        } else {
            LOGGER.fine("Refreshed base subscription JWT token");
            baseSubscriptionToken = token;
            cb.Done(null, token);
        }
    }

    private void handleGradualTokenRefresh(String channel, TokenCallback cb) {
        String[] parts = gradualRolloutManager.parseGradualChannel(channel);
        if (parts == null) {
            LOGGER.severe("Failed to parse gradual channel for token refresh: " + channel);
            cb.Done(new RuntimeException("Failed to parse channel"), null);
            return;
        }

        String token = tokenFetcher.fetchSubscriptionTokenForGradualChannel(parts[0], Integer.parseInt(parts[1]));
        if (token == null) {
            LOGGER.severe("Failed to refresh gradual subscription JWT token for channel: " + channel);
            cb.Done(new RuntimeException("Failed to obtain subscription JWT token"), null);
        } else {
            LOGGER.fine("Refreshed gradual subscription JWT token for channel: " + channel);
            cb.Done(null, token);
        }
    }
}
