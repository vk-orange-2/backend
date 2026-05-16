package ru.itmo.config_streamer.sdk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GradualRolloutManagerTest {

    private static final String BASE_CHANNEL = "service:myservice:dev";

    @Test
    @DisplayName("calculateDeploymentBucket returns consistent results for same instance name")
    void testConsistentBuckets() {
        String instanceName = "instance-12345";
        GradualRolloutManager manager = new GradualRolloutManager(BASE_CHANNEL, instanceName);

        // Same instance should always get same bucket
        int bucket1 = manager.calculateDeploymentBucket(20);
        int bucket2 = manager.calculateDeploymentBucket(20);
        int bucket3 = manager.calculateDeploymentBucket(20);

        assertEquals(bucket1, bucket2, "Same instance should get consistent bucket");
        assertEquals(bucket1, bucket3, "Same instance should get consistent bucket");
    }

    @Test
    @DisplayName("calculateDeploymentBucket distributes instances across buckets")
    void testDistributionAcrossBuckets() {
        int deployments = 20;
        int[] bucketCounts = new int[deployments];

        // Test with 1000 different instance names
        for (int i = 0; i < 1000; i++) {
            GradualRolloutManager manager = new GradualRolloutManager(BASE_CHANNEL, "instance-" + i);
            int bucket = manager.calculateDeploymentBucket(deployments);
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
        for (int i = 0; i < 100; i++) {
            GradualRolloutManager manager = new GradualRolloutManager(BASE_CHANNEL, "instance-" + i);
            int deployment = manager.calculateDeploymentBucket(20);
            
            assertTrue(deployment >= 1 && deployment <= 20, 
                    "Deployment should be between 1 and 20 for 20 deployments, got: " + deployment);
        }
    }

    @Test
    @DisplayName("calculateDeploymentBucket returns valid deployment number for 10 deployments")
    void testValidDeploymentFor10Deployments() {
        for (int i = 0; i < 100; i++) {
            GradualRolloutManager manager = new GradualRolloutManager(BASE_CHANNEL, "instance-" + i);
            int deployment = manager.calculateDeploymentBucket(10);
            
            assertTrue(deployment >= 1 && deployment <= 10, 
                    "Deployment should be between 1 and 10 for 10 deployments, got: " + deployment);
        }
    }

    @Test
    @DisplayName("calculateDeploymentBucket returns valid deployment number for 4 deployments")
    void testValidDeploymentFor4Deployments() {
        for (int i = 0; i < 100; i++) {
            GradualRolloutManager manager = new GradualRolloutManager(BASE_CHANNEL, "instance-" + i);
            int deployment = manager.calculateDeploymentBucket(4);
            
            assertTrue(deployment >= 1 && deployment <= 4, 
                    "Deployment should be between 1 and 4 for 4 deployments, got: " + deployment);
        }
    }

    @Test
    @DisplayName("buildGradualChannel constructs correct channel name")
    void testBuildGradualChannel() {
        GradualRolloutManager manager = new GradualRolloutManager(BASE_CHANNEL, "instance-1");

        String channel = manager.buildGradualChannel("feature-x", 1);
        assertEquals("service:myservice:dev:feature-x:1", channel);

        channel = manager.buildGradualChannel("my-config", 5);
        assertEquals("service:myservice:dev:my-config:5", channel);

        channel = manager.buildGradualChannel("rollout-key", 10);
        assertEquals("service:myservice:dev:rollout-key:10", channel);
    }

    @Test
    @DisplayName("parseGradualChannel extracts key and deployment number correctly")
    void testParseGradualChannel() {
        GradualRolloutManager manager = new GradualRolloutManager(BASE_CHANNEL, "instance-1");

        String[] result = manager.parseGradualChannel("service:myservice:dev:feature-x:1");
        assertNotNull(result);
        assertEquals("feature-x", result[0]);
        assertEquals("1", result[1]);

        result = manager.parseGradualChannel("service:myservice:dev:my-config:5");
        assertNotNull(result);
        assertEquals("my-config", result[0]);
        assertEquals("5", result[1]);
    }

    @Test
    @DisplayName("parseGradualChannel returns null for invalid channels")
    void testParseGradualChannelInvalid() {
        GradualRolloutManager manager = new GradualRolloutManager(BASE_CHANNEL, "instance-1");

        // Base channel (not gradual)
        assertNull(manager.parseGradualChannel("service:myservice:dev"));

        // Wrong base channel
        assertNull(manager.parseGradualChannel("service:other:prod:feature-x:1"));

        // Non-numeric deployment number
        assertNull(manager.parseGradualChannel("service:myservice:dev:feature-x:abc"));

        // No colon between key and deployment
        assertNull(manager.parseGradualChannel("service:myservice:dev:feature-x1"));
    }

    @Test
    @DisplayName("isGradualChannel correctly identifies gradual channels")
    void testIsGradualChannel() {
        GradualRolloutManager manager = new GradualRolloutManager(BASE_CHANNEL, "instance-1");

        // Gradual channels (end with deployment number)
        assertTrue(manager.isGradualChannel("service:myservice:dev:feature-x:1"));
        assertTrue(manager.isGradualChannel("service:other:prod:key:10"));
        assertTrue(manager.isGradualChannel("a:b:c:1"));

        // Not gradual channels
        assertFalse(manager.isGradualChannel("service:myservice:dev"));
        assertFalse(manager.isGradualChannel("service:myservice:dev:"));
        assertFalse(manager.isGradualChannel(null));
        assertFalse(manager.isGradualChannel("service:myservice:dev:feature-x"));
    }

    @Test
    @DisplayName("Different instance names get different buckets")
    void testDifferentInstancesDifferentBuckets() {
        GradualRolloutManager manager1 = new GradualRolloutManager(BASE_CHANNEL, "instance-1");
        GradualRolloutManager manager2 = new GradualRolloutManager(BASE_CHANNEL, "instance-2");
        GradualRolloutManager manager3 = new GradualRolloutManager(BASE_CHANNEL, "instance-3");

        int bucket1 = manager1.calculateDeploymentBucket(20);
        int bucket2 = manager2.calculateDeploymentBucket(20);
        int bucket3 = manager3.calculateDeploymentBucket(20);

        // At least one should be different (extremely unlikely all 3 are same)
        boolean allSame = (bucket1 == bucket2) && (bucket2 == bucket3);
        assertFalse(allSame, "Different instances should have different bucket distribution");
    }
}
