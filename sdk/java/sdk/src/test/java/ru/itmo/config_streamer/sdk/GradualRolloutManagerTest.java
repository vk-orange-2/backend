package ru.itmo.config_streamer.sdk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GradualRolloutManagerTest {

    private static final String BASE_CHANNEL = "service:myservice:dev";

    @Test
    @DisplayName("calculatePercentageBucket returns consistent results for same instance name")
    void testConsistentBuckets() {
        String instanceName = "instance-12345";
        GradualRolloutManager manager = new GradualRolloutManager(BASE_CHANNEL, instanceName);

        // Same instance should always get same bucket
        int bucket1 = manager.calculatePercentageBucket(20);
        int bucket2 = manager.calculatePercentageBucket(20);
        int bucket3 = manager.calculatePercentageBucket(20);

        assertEquals(bucket1, bucket2, "Same instance should get consistent bucket");
        assertEquals(bucket1, bucket3, "Same instance should get consistent bucket");
    }

    @Test
    @DisplayName("calculatePercentageBucket distributes instances across buckets")
    void testDistributionAcrossBuckets() {
        int deployments = 20;
        int[] bucketCounts = new int[deployments];

        // Test with 1000 different instance names
        for (int i = 0; i < 1000; i++) {
            GradualRolloutManager manager = new GradualRolloutManager(BASE_CHANNEL, "instance-" + i);
            int percentage = manager.calculatePercentageBucket(deployments);
            int bucketIndex = percentage / (100 / deployments) - 1;
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
    @DisplayName("calculatePercentageBucket returns valid percentage for 20 deployments")
    void testValidPercentageFor20Deployments() {
        for (int i = 0; i < 100; i++) {
            GradualRolloutManager manager = new GradualRolloutManager(BASE_CHANNEL, "instance-" + i);
            int percentage = manager.calculatePercentageBucket(20);
            
            assertTrue(percentage >= 5 && percentage <= 100, 
                    "Percentage should be between 5 and 100 for 20 deployments, got: " + percentage);
            assertEquals(0, percentage % 5, 
                    "Percentage should be multiple of 5 for 20 deployments, got: " + percentage);
        }
    }

    @Test
    @DisplayName("calculatePercentageBucket returns valid percentage for 10 deployments")
    void testValidPercentageFor10Deployments() {
        for (int i = 0; i < 100; i++) {
            GradualRolloutManager manager = new GradualRolloutManager(BASE_CHANNEL, "instance-" + i);
            int percentage = manager.calculatePercentageBucket(10);
            
            assertTrue(percentage >= 10 && percentage <= 100, 
                    "Percentage should be between 10 and 100 for 10 deployments, got: " + percentage);
            assertEquals(0, percentage % 10, 
                    "Percentage should be multiple of 10 for 10 deployments, got: " + percentage);
        }
    }

    @Test
    @DisplayName("calculatePercentageBucket returns valid percentage for 4 deployments")
    void testValidPercentageFor4Deployments() {
        for (int i = 0; i < 100; i++) {
            GradualRolloutManager manager = new GradualRolloutManager(BASE_CHANNEL, "instance-" + i);
            int percentage = manager.calculatePercentageBucket(4);
            
            assertTrue(percentage >= 25 && percentage <= 100, 
                    "Percentage should be between 25 and 100 for 4 deployments, got: " + percentage);
            assertEquals(0, percentage % 25, 
                    "Percentage should be multiple of 25 for 4 deployments, got: " + percentage);
        }
    }

    @Test
    @DisplayName("buildGradualChannel constructs correct channel name")
    void testBuildGradualChannel() {
        GradualRolloutManager manager = new GradualRolloutManager(BASE_CHANNEL, "instance-1");

        String channel = manager.buildGradualChannel("feature-x", 25);
        assertEquals("service:myservice:dev:feature-x:25%", channel);

        channel = manager.buildGradualChannel("my-config", 50);
        assertEquals("service:myservice:dev:my-config:50%", channel);

        channel = manager.buildGradualChannel("rollout-key", 100);
        assertEquals("service:myservice:dev:rollout-key:100%", channel);
    }

    @Test
    @DisplayName("parseGradualChannel extracts key and percentage correctly")
    void testParseGradualChannel() {
        GradualRolloutManager manager = new GradualRolloutManager(BASE_CHANNEL, "instance-1");

        String[] result = manager.parseGradualChannel("service:myservice:dev:feature-x:25%");
        assertNotNull(result);
        assertEquals("feature-x", result[0]);
        assertEquals("25", result[1]);

        result = manager.parseGradualChannel("service:myservice:dev:my-config:50%");
        assertNotNull(result);
        assertEquals("my-config", result[0]);
        assertEquals("50", result[1]);
    }

    @Test
    @DisplayName("parseGradualChannel returns null for invalid channels")
    void testParseGradualChannelInvalid() {
        GradualRolloutManager manager = new GradualRolloutManager(BASE_CHANNEL, "instance-1");

        // Base channel (not gradual)
        assertNull(manager.parseGradualChannel("service:myservice:dev"));

        // Wrong base channel
        assertNull(manager.parseGradualChannel("service:other:prod:feature-x:25%"));

        // Missing percentage sign
        assertNull(manager.parseGradualChannel("service:myservice:dev:feature-x:25"));

        // No colon between key and percentage
        assertNull(manager.parseGradualChannel("service:myservice:dev:feature-x25%"));
    }

    @Test
    @DisplayName("isGradualChannel correctly identifies gradual channels")
    void testIsGradualChannel() {
        GradualRolloutManager manager = new GradualRolloutManager(BASE_CHANNEL, "instance-1");

        // Gradual channels
        assertTrue(manager.isGradualChannel("service:myservice:dev:feature-x:25%"));
        assertTrue(manager.isGradualChannel("service:other:prod:key:100%"));
        assertTrue(manager.isGradualChannel("a:b:c:1%"));

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

        int bucket1 = manager1.calculatePercentageBucket(20);
        int bucket2 = manager2.calculatePercentageBucket(20);
        int bucket3 = manager3.calculatePercentageBucket(20);

        // At least one should be different (extremely unlikely all 3 are same)
        boolean allSame = (bucket1 == bucket2) && (bucket2 == bucket3);
        assertFalse(allSame, "Different instances should have different bucket distribution");
    }
}
