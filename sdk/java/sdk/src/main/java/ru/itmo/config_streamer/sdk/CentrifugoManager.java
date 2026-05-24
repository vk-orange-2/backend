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
 * Manages Centrifugo WebSocket connection and subscription to the main channel.
 */
class CentrifugoManager {
    private static final Logger LOGGER = Logger.getLogger(CentrifugoManager.class.getName());

    private final String wsUrl;
    private final String baseChannel;
    private final TokenFetcher tokenFetcher;
    private final PublicationHandler publicationHandler;

    private io.github.centrifugal.centrifuge.Client centrifugoClient;
    private Subscription baseSubscription;
    private String baseSubscriptionToken;

    /**
     * Functional interface for handling publications.
     */
    interface PublicationHandler {
        void handle(PublicationEvent event);
    }

    CentrifugoManager(String baseUrl, String baseChannel, TokenFetcher tokenFetcher, PublicationHandler publicationHandler) {
        this.wsUrl = baseUrl.replace("http://", "ws://")
                .replace("https://", "wss://")
                + "/centrifugo/connection/websocket";
        this.baseChannel = baseChannel;
        this.tokenFetcher = tokenFetcher;
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
        LOGGER.info("Connected to Centrifugo and subscribed to channel: " + baseChannel);
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
                    publicationHandler.handle(event);
                }
            };

            baseSubscription = centrifugoClient.newSubscription(baseChannel, subOptions, subListener);
            baseSubscription.subscribe();

            LOGGER.info("Subscribed to channel: " + baseChannel);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error subscribing to channel: " + baseChannel, e);
        }
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
            LOGGER.severe("Failed to refresh subscription JWT token");
            cb.Done(new RuntimeException("Failed to obtain subscription JWT token"), null);
        } else {
            LOGGER.fine("Refreshed subscription JWT token");
            baseSubscriptionToken = token;
            cb.Done(null, token);
        }
    }
}
