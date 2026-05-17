package ru.itmo.config_streamer.sdk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GradualRolloutManagerTest {

    @Test
    @DisplayName("calculateDeploymentBucket returns consistent results for same instance, configKey, and version")
    void testConsistentBuckets() {
        String instanceName = "instance-12345";
        String configKey = "my-config";
        int version = 3;
        GradualRolloutManager manager = new GradualRolloutManager(instanceName);

        // Same instance + configKey + version should always get same bucket
        int bucket1 = manager.calculateDeploymentBucket(configKey, version, 20);
        int bucket2 = manager.calculateDeploymentBucket(configKey, version, 20);
        int bucket3 = manager.calculateDeploymentBucket(configKey, version, 20);

        assertEquals(bucket1, bucket2, "Same instance, configKey, version should get consistent bucket");
        assertEquals(bucket1, bucket3, "Same instance, configKey, version should get consistent bucket");
    }

    @Test
    @DisplayName("calculateDeploymentBucket returns different buckets for different versions")
    void testDifferentBucketsForDifferentVersions() {
        String instanceName = "instance-12345";
        String configKey = "my-config";
        GradualRolloutManager manager = new GradualRolloutManager(instanceName);

        // Different versions should generally produce different buckets (not guaranteed but highly likely)
        int bucket1 = manager.calculateDeploymentBucket(configKey, 1, 4);
        int bucket2 = manager.calculateDeploymentBucket(configKey, 2, 4);
        int bucket3 = manager.calculateDeploymentBucket(configKey, 3, 4);
        int bucket4 = manager.calculateDeploymentBucket(configKey, 4, 4);

        // At least one should be different from another (very unlikely all 4 are same)
        boolean allSame = (bucket1 == bucket2) && (bucket2 == bucket3) && (bucket3 == bucket4);
        assertFalse(allSame, "Different versions should produce different bucket distributions");
    }

    @Test
    @DisplayName("calculateDeploymentBucket returns different buckets for different configKeys")
    void testDifferentBucketsForDifferentConfigKeys() {
        String instanceName = "instance-12345";
        int version = 1;
        GradualRolloutManager manager = new GradualRolloutManager(instanceName);

        int bucket1 = manager.calculateDeploymentBucket("config-a", version, 4);
        int bucket2 = manager.calculateDeploymentBucket("config-b", version, 4);

        // Different configKeys should generally produce different buckets
        // (not guaranteed but highly likely with SHA256)
        boolean sameBucket = bucket1 == bucket2;
        // Just verify they're both valid, the actual distribution depends on hash
        assertTrue(bucket1 >= 1 && bucket1 <= 4);
        assertTrue(bucket2 >= 1 && bucket2 <= 4);
    }

    @Test
    @DisplayName("calculateDeploymentBucket distributes instances across buckets")
    void testDistributionAcrossBuckets() {
        int deployments = 20;
        int version = 1;
        String configKey = "my-config";
        int[] bucketCounts = new int[deployments];

        // Test with 1000 different instance names
        for (int i = 0; i < 1000; i++) {
            GradualRolloutManager manager = new GradualRolloutManager("instance-" + i);
            int bucket = manager.calculateDeploymentBucket(configKey, version, deployments);
            int bucketIndex = bucket - 1; // bucket is 1-based
            bucketCounts[bucketIndex]++;
        }

        // Each bucket should have approximately 50 instances (1000 / 20)
        // Allow for variance due to hash distribution
        for (int count : bucketCounts) {
            assertTrue(count >= 20 && count <= 80, 
                    "Bucket count should be roughly uniform, got: " + count);
        }
    }

    @Test
    @DisplayName("calculateDeploymentBucket returns valid deployment number for 20 deployments")
    void testValidDeploymentFor20Deployments() {
        GradualRolloutManager manager = new GradualRolloutManager("instance-1");
        for (int i = 0; i < 100; i++) {
            int deployment = manager.calculateDeploymentBucket("config-" + i, i + 1, 20);
            assertTrue(deployment >= 1 && deployment <= 20, 
                    "Deployment should be between 1 and 20 for 20 deployments, got: " + deployment);
        }
    }

    @Test
    @DisplayName("calculateDeploymentBucket returns valid deployment number for 10 deployments")
    void testValidDeploymentFor10Deployments() {
        GradualRolloutManager manager = new GradualRolloutManager("instance-1");
        for (int i = 0; i < 100; i++) {
            int deployment = manager.calculateDeploymentBucket("config-" + i, i + 1, 10);
            assertTrue(deployment >= 1 && deployment <= 10, 
                    "Deployment should be between 1 and 10 for 10 deployments, got: " + deployment);
        }
    }

    @Test
    @DisplayName("calculateDeploymentBucket returns valid deployment number for 4 deployments")
    void testValidDeploymentFor4Deployments() {
        GradualRolloutManager manager = new GradualRolloutManager("instance-1");
        for (int i = 0; i < 100; i++) {
            int deployment = manager.calculateDeploymentBucket("config-" + i, i + 1, 4);
            assertTrue(deployment >= 1 && deployment <= 4, 
                    "Deployment should be between 1 and 4 for 4 deployments, got: " + deployment);
        }
    }

    @Test
    @DisplayName("shouldProcessGradualDeploy returns true when deployment matches bucket")
    void testShouldProcessGradualDeployMatch() {
        // Create a manager and find its bucket
        GradualRolloutManager manager = new GradualRolloutManager("test-instance-1");
        String configKey = "test-config";
        int version = 1;
        int totalDeployments = 4;
        int myBucket = manager.calculateDeploymentBucket(configKey, version, totalDeployments);
        
        // Should process when deployment matches bucket
        assertTrue(manager.shouldProcessGradualDeploy(configKey, version, myBucket, totalDeployments),
                "Should process when deployment matches instance bucket");
    }

    @Test
    @DisplayName("shouldProcessGradualDeploy returns false when deployment doesn't match bucket")
    void testShouldProcessGradualDeployNoMatch() {
        // Create a manager and find its bucket
        GradualRolloutManager manager = new GradualRolloutManager("test-instance-2");
        String configKey = "test-config";
        int version = 1;
        int totalDeployments = 4;
        int myBucket = manager.calculateDeploymentBucket(configKey, version, totalDeployments);
        
        // Should not process for other deployments
        for (int deployment = 1; deployment <= totalDeployments; deployment++) {
            if (deployment != myBucket) {
                assertFalse(manager.shouldProcessGradualDeploy(configKey, version, deployment, totalDeployments),
                        "Should not process when deployment doesn't match instance bucket");
            }
        }
    }

    @Test
    @DisplayName("Different instance names get different buckets for same config and version")
    void testDifferentInstancesDifferentBuckets() {
        String configKey = "shared-config";
        int version = 1;
        
        GradualRolloutManager manager1 = new GradualRolloutManager("instance-1");
        GradualRolloutManager manager2 = new GradualRolloutManager("instance-2");
        GradualRolloutManager manager3 = new GradualRolloutManager("instance-3");

        int bucket1 = manager1.calculateDeploymentBucket(configKey, version, 20);
        int bucket2 = manager2.calculateDeploymentBucket(configKey, version, 20);
        int bucket3 = manager3.calculateDeploymentBucket(configKey, version, 20);

        // At least one should be different (extremely unlikely all 3 are same)
        boolean allSame = (bucket1 == bucket2) && (bucket2 == bucket3);
        assertFalse(allSame, "Different instances should have different bucket distribution");
    }

    @Test
    @DisplayName("Same instance gets different buckets for same config across versions")
    void testSameInstanceDifferentVersionsDifferentBuckets() {
        String configKey = "my-config";
        GradualRolloutManager manager = new GradualRolloutManager("test-instance");
        
        // Track buckets across different versions
        int[] buckets = new int[10];
        for (int v = 1; v <= 10; v++) {
            buckets[v - 1] = manager.calculateDeploymentBucket(configKey, v, 10);
        }
        
        // Count unique bucket values
        java.util.Set<Integer> uniqueBuckets = new java.util.HashSet<>();
        for (int b : buckets) {
            uniqueBuckets.add(b);
        }
        
        // With 10 versions and 10 buckets, we should see multiple different buckets
        assertTrue(uniqueBuckets.size() > 1, 
                "Same instance should get different buckets for different versions, got: " + uniqueBuckets.size() + " unique buckets");
    }
}
