package ru.itmo.config_streamer.sdk;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles gradual rollout calculations and channel management.
 */
class GradualRolloutManager {
    private static final Logger LOGGER = Logger.getLogger(GradualRolloutManager.class.getName());

    private final String baseChannel;
    private final String instanceName;

    GradualRolloutManager(String baseChannel, String instanceName) {
        this.baseChannel = baseChannel;
        this.instanceName = instanceName;
    }

    /**
     * Calculates the deployment bucket number for gradual rollout using SHA256 hash.
     *
     * @param deployments the total number of deployment batches (e.g., 10)
     * @return the deployment number (1 to deployments)
     */
    int calculateDeploymentBucket(int deployments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(instanceName.getBytes(StandardCharsets.UTF_8));
            // Use first 4 bytes as integer
            int hashInt = Math.abs(ByteBuffer.wrap(hash, 0, 4).getInt());
            // Determine which bucket (0 to deployments-1), then add 1 for 1-based numbering
            int bucketNumber = (hashInt % deployments) + 1;
            LOGGER.info("Calculated gradual rollout bucket for instance " + instanceName +
                    ": deployment " + bucketNumber + " of " + deployments);
            return bucketNumber;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error calculating deployment bucket", e);
            return deployments; // Fallback to last deployment
        }
    }

    /**
     * Builds a gradual rollout channel name.
     * Format: service:<service>:<env>:<key>:<deploymentNumber>
     *
     * @param rolloutKey the rollout key
     * @param deploymentNumber the deployment number (1 to deployments)
     * @return the full channel name
     */
    String buildGradualChannel(String rolloutKey, int deploymentNumber) {
        return baseChannel + ":" + rolloutKey + ":" + deploymentNumber;
    }

    /**
     * Parses a gradual rollout channel to extract the rollout key and deployment number.
     * Channel format: service:<service>:<env>:<key>:<deploymentNumber>
     *
     * @param channel the gradual rollout channel
     * @return an array with [rolloutKey, deploymentNumber] or null if parsing fails
     */
    String[] parseGradualChannel(String channel) {
        // Extract the suffix after baseChannel:
        if (!channel.startsWith(baseChannel + ":")) {
            return null;
        }
        String suffix = channel.substring(baseChannel.length() + 1);
        // Format: <key>:<deploymentNumber>
        int lastColon = suffix.lastIndexOf(':');
        if (lastColon == -1) {
            return null;
        }
        String key = suffix.substring(0, lastColon);
        String deploymentNum = suffix.substring(lastColon + 1);
        try {
            Integer.parseInt(deploymentNum);
        } catch (NumberFormatException e) {
            return null;
        }
        return new String[]{key, deploymentNum};
    }

    /**
     * Checks if a channel is a gradual rollout channel.
     *
     * @param channel the channel to check
     * @return true if it's a gradual rollout channel
     */
    boolean isGradualChannel(String channel) {
        return channel != null && channel.matches(".+:.+:\\d+$");
    }
}
