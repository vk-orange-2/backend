package ru.itmo.config_streamer.sdk;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles gradual rollout and canary hash calculations.
 * Determines which deployment bucket an instance belongs to based on SHA256 hash.
 * For gradual rollout: hash is computed from instanceName + configKey + version.
 * For canary: hash is computed from instanceName only to determine canary membership.
 */
class GradualRolloutManager {
    private static final Logger LOGGER = Logger.getLogger(GradualRolloutManager.class.getName());

    private final String instanceName;
    private final int instanceHash; // Cached hash for canary calculations

    GradualRolloutManager(String instanceName) {
        this.instanceName = instanceName;
        this.instanceHash = calculateInstanceHash(instanceName);
    }

    /**
     * Calculates a hash value (0-99) for the instance name.
     * Used to determine canary membership.
     */
    private int calculateInstanceHash(String instanceName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(instanceName.getBytes(StandardCharsets.UTF_8));
            // Use first 4 bytes as integer, then mod 100 for percentage
            int hashInt = Math.abs(ByteBuffer.wrap(hash, 0, 4).getInt());
            return hashInt % 100;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error calculating instance hash", e);
            return 99; // Fallback to not being in canary
        }
    }

    /**
     * Checks if this instance should be part of a canary deployment.
     * An instance is in canary if: hash(instanceName) % 100 < canaryPercentage
     *
     * @param canaryPercentage the percentage of instances that should receive canary
     * @return true if this instance should receive the canary update
     */
    boolean isInCanary(int canaryPercentage) {
        boolean inCanary = instanceHash < canaryPercentage;
        if (inCanary) {
            LOGGER.info("Instance " + instanceName + " is in canary (hash=" + instanceHash + 
                    ", percentage=" + canaryPercentage + ")");
        } else {
            LOGGER.fine("Instance " + instanceName + " is NOT in canary (hash=" + instanceHash + 
                    ", percentage=" + canaryPercentage + ")");
        }
        return inCanary;
    }

    /**
     * Calculates the deployment bucket number for gradual rollout using SHA256 hash.
     * The hash is computed from instanceName + configKey + version to ensure different
     * instance ordering for each deployment.
     *
     * @param configKey the configuration key
     * @param version the configuration version
     * @param totalDeployments the total number of deployment batches (e.g., 4)
     * @return the deployment number (1 to totalDeployments)
     */
    int calculateDeploymentBucket(String configKey, long version, int totalDeployments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Combine instanceName + configKey + version for hash input
            String hashInput = instanceName + ":" + configKey + ":" + version;
            byte[] hash = digest.digest(hashInput.getBytes(StandardCharsets.UTF_8));
            // Use first 4 bytes as integer
            int hashInt = Math.abs(ByteBuffer.wrap(hash, 0, 4).getInt());
            // Determine which bucket (0 to totalDeployments-1), then add 1 for 1-based numbering
            int bucketNumber = (hashInt % totalDeployments) + 1;
            LOGGER.fine("Calculated gradual rollout bucket for instance " + instanceName +
                    ", config " + configKey + " v" + version + ": deployment " + bucketNumber + " of " + totalDeployments);
            return bucketNumber;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error calculating deployment bucket", e);
            return totalDeployments; // Fallback to last deployment
        }
    }

    /**
     * Checks if this instance should process a gradual_deploy message.
     *
     * @param configKey the configuration key
     * @param version the configuration version
     * @param messageDeployment the deployment number from the message
     * @param totalDeployments the total number of deployments from the message
     * @return true if this instance's bucket matches the message deployment number
     */
    boolean shouldProcessGradualDeploy(String configKey, long version, int messageDeployment, int totalDeployments) {
        int myBucket = calculateDeploymentBucket(configKey, version, totalDeployments);
        boolean shouldProcess = myBucket == messageDeployment;
        if (shouldProcess) {
            LOGGER.info("Processing gradual_deploy: deployment " + messageDeployment + 
                    " of " + totalDeployments + " matches instance bucket " + myBucket +
                    " for config " + configKey + " v" + version);
        } else {
            LOGGER.fine("Ignoring gradual_deploy: deployment " + messageDeployment + 
                    " of " + totalDeployments + " does not match instance bucket " + myBucket +
                    " for config " + configKey + " v" + version);
        }
        return shouldProcess;
    }

    /**
     * Returns the instance name.
     */
    String getInstanceName() {
        return instanceName;
    }

    /**
     * Returns the cached instance hash (0-99).
     */
    int getInstanceHash() {
        return instanceHash;
    }
}
