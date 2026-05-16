package ru.itmo.config_streamer.sdk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GradualRolloutManagerTest {

    @Test
    @DisplayName("calculateDeploymentBucket returns consistent results for same instance name")
    void testConsistentBuckets() {
        String instanceName = "instance-12345";
        GradualRolloutManager manager = new GradualRolloutManager(instanceName);

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
            GradualRolloutManager manager = new GradualRolloutManager("instance-" + i);
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
            GradualRolloutManager manager = new GradualRolloutManager("instance-" + i);
            int deployment = manager.calculateDeploymentBucket(20);
            
            assertTrue(deployment >= 1 && deployment <= 20, 
                    "Deployment should be between 1 and 20 for 20 deployments, got: " + deployment);
        }
    }

    @Test
    @DisplayName("calculateDeploymentBucket returns valid deployment number for 10 deployments")
    void testValidDeploymentFor10Deployments() {
        for (int i = 0; i < 100; i++) {
            GradualRolloutManager manager = new GradualRolloutManager("instance-" + i);
            int deployment = manager.calculateDeploymentBucket(10);
            
            assertTrue(deployment >= 1 && deployment <= 10, 
                    "Deployment should be between 1 and 10 for 10 deployments, got: " + deployment);
        }
    }

    @Test
    @DisplayName("calculateDeploymentBucket returns valid deployment number for 4 deployments")
    void testValidDeploymentFor4Deployments() {
        for (int i = 0; i < 100; i++) {
            GradualRolloutManager manager = new GradualRolloutManager("instance-" + i);
            int deployment = manager.calculateDeploymentBucket(4);
            
            assertTrue(deployment >= 1 && deployment <= 4, 
                    "Deployment should be between 1 and 4 for 4 deployments, got: " + deployment);
        }
    }

    @Test
    @DisplayName("shouldProcessGradualDeploy returns true when deployment matches bucket")
    void testShouldProcessGradualDeployMatch() {
        // Create a manager and find its bucket
        GradualRolloutManager manager = new GradualRolloutManager("test-instance-1");
        int totalDeployments = 4;
        int myBucket = manager.calculateDeploymentBucket(totalDeployments);
        
        // Should process when deployment matches bucket
        assertTrue(manager.shouldProcessGradualDeploy(myBucket, totalDeployments),
                "Should process when deployment matches instance bucket");
    }

    @Test
    @DisplayName("shouldProcessGradualDeploy returns false when deployment doesn't match bucket")
    void testShouldProcessGradualDeployNoMatch() {
        // Create a manager and find its bucket
        GradualRolloutManager manager = new GradualRolloutManager("test-instance-2");
        int totalDeployments = 4;
        int myBucket = manager.calculateDeploymentBucket(totalDeployments);
        
        // Should not process for other deployments
        for (int deployment = 1; deployment <= totalDeployments; deployment++) {
            if (deployment != myBucket) {
                assertFalse(manager.shouldProcessGradualDeploy(deployment, totalDeployments),
                        "Should not process when deployment doesn't match instance bucket");
            }
        }
    }

    @Test
    @DisplayName("Different instance names get different buckets")
    void testDifferentInstancesDifferentBuckets() {
        GradualRolloutManager manager1 = new GradualRolloutManager("instance-1");
        GradualRolloutManager manager2 = new GradualRolloutManager("instance-2");
        GradualRolloutManager manager3 = new GradualRolloutManager("instance-3");

        int bucket1 = manager1.calculateDeploymentBucket(20);
        int bucket2 = manager2.calculateDeploymentBucket(20);
        int bucket3 = manager3.calculateDeploymentBucket(20);

        // At least one should be different (extremely unlikely all 3 are same)
        boolean allSame = (bucket1 == bucket2) && (bucket2 == bucket3);
        assertFalse(allSame, "Different instances should have different bucket distribution");
    }

    @Test
    @DisplayName("Bucket calculation is deterministic across different total deployment counts")
    void testDeterministicAcrossDeploymentCounts() {
        GradualRolloutManager manager = new GradualRolloutManager("test-instance-deterministic");
        
        // Get bucket for 4 deployments
        int bucket4 = manager.calculateDeploymentBucket(4);
        
        // Get bucket for 20 deployments
        int bucket20 = manager.calculateDeploymentBucket(20);
        
        // Both should be valid and consistent for same instance
        assertTrue(bucket4 >= 1 && bucket4 <= 4);
        assertTrue(bucket20 >= 1 && bucket20 <= 20);
        
        // Same instance calling multiple times should get same results
        assertEquals(bucket4, manager.calculateDeploymentBucket(4));
        assertEquals(bucket20, manager.calculateDeploymentBucket(20));
    }
}
